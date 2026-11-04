package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.pg.PgDeclinedException;
import dev.wisehero.paypalsandbox.pg.PgTimeoutException;
import dev.wisehero.paypalsandbox.product.ProductRepository;
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
public class SplitTransactionCheckout {

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final PaymentGateway gateway;
    private final PaymentConfirmation confirmation;
    private final FaultInjector faults;
    private final TransactionTemplate tx;

    SplitTransactionCheckout(PurchaseOrderRepository orders, ProductRepository products, PaymentGateway gateway,
            PaymentConfirmation confirmation, FaultInjector faults, TransactionTemplate tx) {
        this.orders = orders;
        this.products = products;
        this.gateway = gateway;
        this.confirmation = confirmation;
        this.faults = faults;
        this.tx = tx;
    }

    public PurchaseOrder capture(Long orderId) {
        String pgOrderId = tx.execute(status -> {
            PurchaseOrder order = lock(orderId);
            order.startPayment();
            products.findByIdForUpdate(order.getProductId()).orElseThrow().decreaseStock(order.getQuantity());
            return order.getPgOrderId();
        });

        PaymentGateway.CaptureResult result;
        try {
            result = gateway.capture(pgOrderId, "capture-" + orderId);
        } catch (PgDeclinedException e) {
            tx.executeWithoutResult(status -> {
                PurchaseOrder order = lock(orderId);
                order.markFailed(e.getMessage());
                products.findByIdForUpdate(order.getProductId()).orElseThrow().increaseStock(order.getQuantity());
            });
            throw e;
        } catch (PgTimeoutException e) {
            // 실패로 단정하지 않는다. 재고도 그대로 잡아 두고 대사에 맡긴다.
            tx.executeWithoutResult(status -> lock(orderId).markUnknown());
            throw e;
        }

        return tx.execute(status -> {
            PurchaseOrder order = lock(orderId);
            confirmation.confirm(order, result.captureId());
            faults.failDbWriteIfRequested();
            return order;
        });
    }

    private PurchaseOrder lock(Long orderId) {
        return orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
