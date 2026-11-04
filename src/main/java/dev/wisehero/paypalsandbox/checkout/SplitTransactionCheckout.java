package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.pg.PgDeclinedException;
import dev.wisehero.paypalsandbox.pg.PgTimeoutException;
import dev.wisehero.paypalsandbox.product.StockLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구조 B: PG 캡처를 트랜잭션 밖으로 빼고 앞뒤를 짧은 트랜잭션으로 나눈다.
 *
 * <ol>
 *   <li>트랜잭션 1: 주문을 PAYMENT_IN_PROGRESS로 바꾸고 재고를 예약한다. 바로 커밋.</li>
 *   <li>트랜잭션 없이 PG 캡처. 멱등 키는 주문 ID로 고정한다.</li>
 *   <li>트랜잭션 2: PAID로 바꾸고 결제 기록과 outbox 이벤트를 남긴다.</li>
 * </ol>
 * 2와 3 사이에서 무슨 일이 생겨도 주문은 PAYMENT_IN_PROGRESS나 PAYMENT_UNKNOWN으로 남는다.
 * 이 흔적을 {@link PaymentReconciler}가 PG 상태와 맞춘다.
 */
@Service
@RequiredArgsConstructor
public class SplitTransactionCheckout {

    private final PurchaseOrderRepository orders;
    private final StockLedger stock;
    private final PaymentGateway gateway;
    private final PaymentConfirmation confirmation;
    private final FaultInjector faults;
    private final TransactionTemplate tx;

    public PurchaseOrder capture(Long orderId) {
        String pgOrderId = startPayment(orderId);

        PaymentGateway.CaptureResult result;
        try {
            result = gateway.capture(pgOrderId, IdempotencyKeys.capture(orderId));
        } catch (PgDeclinedException e) {
            markFailed(orderId, e.getMessage());
            throw e;
        } catch (PgTimeoutException e) {
            markUnknown(orderId);
            throw e;
        }
        return confirm(orderId, result.captureId());
    }

    /** 트랜잭션 1: 결제 시작 흔적을 남기고 재고를 예약한 뒤 바로 커밋한다. */
    private String startPayment(Long orderId) {
        return tx.execute(status -> {
            PurchaseOrder order = orders.lockOrThrow(orderId);
            order.startPayment();
            stock.reserve(order);
            return order.getPgOrderId();
        });
    }

    /** PG가 확실히 거절했다. 예약한 재고를 돌려준다. */
    private void markFailed(Long orderId, String reason) {
        tx.executeWithoutResult(status -> {
            PurchaseOrder order = orders.lockOrThrow(orderId);
            stock.release(order);
            order.markFailed(reason);
        });
    }

    /** 결제됐는지 모른다. 실패로 단정하지 않고 재고도 그대로 잡아 둔 채 대사에 맡긴다. */
    private void markUnknown(Long orderId) {
        tx.executeWithoutResult(status -> orders.lockOrThrow(orderId).markUnknown());
    }

    /** 트랜잭션 2: 결제 완료로 확정한다. */
    private PurchaseOrder confirm(Long orderId, String captureId) {
        return tx.execute(status -> {
            PurchaseOrder order = orders.lockOrThrow(orderId);
            confirmation.confirm(order, captureId);
            faults.failDbWriteIfRequested();
            return order;
        });
    }
}
