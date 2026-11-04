package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.product.StockLedger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대사: PG 결과를 기다리는 주문을 PG의 실제 상태와 맞춘다.
 *
 * <ul>
 *   <li>PG가 캡처 완료 → 주문을 PAID로 확정한다.</li>
 *   <li>PG가 캡처 안 함 → 주문을 실패 처리하고 예약한 재고를 돌려준다.</li>
 *   <li>주문은 이미 실패인데 PG가 캡처 완료 → 환불한다 (보상).</li>
 * </ul>
 * PG 조회와 환불은 트랜잭션 밖에서 부른다.
 */
@Service
@RequiredArgsConstructor
public class PaymentReconciler {

    private static final List<OrderStatus> AWAITING =
            List.of(OrderStatus.PAYMENT_IN_PROGRESS, OrderStatus.PAYMENT_UNKNOWN);

    private final PurchaseOrderRepository orders;
    private final StockLedger stock;
    private final PaymentGateway gateway;
    private final PaymentConfirmation confirmation;
    private final TransactionTemplate tx;
    // lombok.config에서 @Value를 생성자 파라미터로 복사하게 해 두었다.
    @Value("${reconcile.stale-after}")
    private final Duration staleAfter;

    /** 결제 중이거나 결과를 모르는 채로 staleAfter가 지난 주문을 모두 대사한다. */
    public List<Result> reconcileStale() {
        Instant cutoff = Instant.now().minus(staleAfter);
        return orders.findByStatusInAndUpdatedAtBefore(AWAITING, cutoff).stream()
                .map(order -> reconcile(order.getId(), true))
                .toList();
    }

    /** 웹훅에서 부른다. 캡처 완료 알림이므로 실패 처리는 하지 않는다. */
    public Result reconcileByPgOrderId(String pgOrderId) {
        PurchaseOrder order = orders.findByPgOrderId(pgOrderId)
                .orElseThrow(() -> new OrderNotFoundException(pgOrderId));
        return reconcile(order.getId(), false);
    }

    /**
     * @param allowFail PG가 캡처하지 않았을 때 주문을 실패로 확정해도 되는지.
     *                  캡처 요청이 아직 PG에서 처리 중일 수 있으므로 충분히 시간이 지난 주문에만 true를 쓴다.
     */
    public Result reconcile(Long orderId, boolean allowFail) {
        PurchaseOrder current = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        PaymentGateway.PgOrderSnapshot snapshot = gateway.getOrder(current.getPgOrderId());

        Result result = tx.execute(status -> {
            PurchaseOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.PAID) {
                return new Result(orderId, Action.ALREADY_PAID, null);
            }
            if (order.isAwaitingPgResult()) {
                if (snapshot.isCaptured()) {
                    confirmation.confirm(order, snapshot.captureId());
                    return new Result(orderId, Action.CONFIRMED_PAID, snapshot.captureId());
                }
                if (allowFail) {
                    stock.release(order);
                    order.markFailed("대사: PG 캡처 없음 (" + snapshot.status() + ")");
                    return new Result(orderId, Action.MARKED_FAILED, null);
                }
                return new Result(orderId, Action.STILL_PENDING, null);
            }
            if (order.getStatus() == OrderStatus.PAYMENT_FAILED && snapshot.isCaptured()) {
                return new Result(orderId, Action.REFUND_REQUIRED, snapshot.captureId());
            }
            return new Result(orderId, Action.NOTHING, null);
        });

        if (result.action() == Action.REFUND_REQUIRED) {
            gateway.refund(result.captureId(), "refund-" + orderId);
            tx.executeWithoutResult(status ->
                    orders.findByIdForUpdate(orderId).orElseThrow().markRefunded(result.captureId()));
            return new Result(orderId, Action.REFUNDED, result.captureId());
        }
        return result;
    }

    public record Result(Long orderId, Action action, String captureId) {
    }

    public enum Action {
        ALREADY_PAID,
        CONFIRMED_PAID,
        MARKED_FAILED,
        STILL_PENDING,
        REFUND_REQUIRED,
        REFUNDED,
        NOTHING
    }
}
