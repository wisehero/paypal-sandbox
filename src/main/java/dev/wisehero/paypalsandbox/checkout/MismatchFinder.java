package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 진단용: PG에서는 캡처가 끝났는데 우리 주문은 결제 완료가 아닌 건을 찾는다.
 * 고객 돈은 빠져나갔는데 주문은 없는 것과 같은 상태다.
 *
 * <p>미결제 주문마다 PG를 조회하므로 운영에서 그대로 쓰기엔 비싸다.
 * 트랜잭션 안 구조에서는 이런 전수 조회 말고는 롤백된 결제를 찾을 방법이 없다는 점을 보여 주려고 만들었다.
 */
@Service
public class MismatchFinder {

    private final PurchaseOrderRepository orders;
    private final PaymentGateway gateway;

    MismatchFinder(PurchaseOrderRepository orders, PaymentGateway gateway) {
        this.orders = orders;
        this.gateway = gateway;
    }

    public List<Mismatch> find() {
        return orders.findByStatusNotInAndPgOrderIdIsNotNull(List.of(OrderStatus.PAID, OrderStatus.REFUNDED))
                .stream()
                .map(this::check)
                .filter(Objects::nonNull)
                .toList();
    }

    private Mismatch check(PurchaseOrder order) {
        PaymentGateway.PgOrderSnapshot snapshot = gateway.getOrder(order.getPgOrderId());
        if (!snapshot.isCaptured()) {
            return null;
        }
        return new Mismatch(order.getId(), order.getStatus(), order.getCaptureStrategy() == null
                ? null : order.getCaptureStrategy().name(), order.getPgOrderId(), snapshot.captureId());
    }

    public record Mismatch(Long orderId, OrderStatus orderStatus, String strategy, String pgOrderId,
            String pgCaptureId) {
    }
}
