package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.product.StockLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구조 A: PG 캡처를 DB 트랜잭션 안에 넣는다.
 *
 * <p>코드는 단순하다. 대신 다음 일이 생긴다.
 * <ul>
 *   <li>PG 응답을 기다리는 동안 DB 커넥션과 주문·상품 행 락을 계속 쥐고 있다.</li>
 *   <li>캡처가 끝난 뒤 DB 반영이 실패하면 롤백되어 주문은 CREATED로 남는다. 돈은 이미 빠져나갔다.</li>
 *   <li>PG가 타임아웃 나도 롤백된다. 실제로 결제가 됐는지는 아무도 모른다.</li>
 * </ul>
 * 롤백된 주문은 평범한 미결제 주문과 구분되지 않아서 나중에 찾기 어렵다.
 */
@Service
public class InTransactionCheckout {

    private final PurchaseOrderRepository orders;
    private final StockLedger stock;
    private final PaymentGateway gateway;
    private final PaymentConfirmation confirmation;
    private final FaultInjector faults;

    InTransactionCheckout(PurchaseOrderRepository orders, StockLedger stock, PaymentGateway gateway,
            PaymentConfirmation confirmation, FaultInjector faults) {
        this.orders = orders;
        this.stock = stock;
        this.gateway = gateway;
        this.confirmation = confirmation;
        this.faults = faults;
    }

    @Transactional
    public PurchaseOrder capture(Long orderId) {
        PurchaseOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.beginInTransactionCapture();

        stock.reserve(order);

        // 커넥션과 락을 쥔 채로 외부 호출
        PaymentGateway.CaptureResult result = gateway.capture(order.getPgOrderId(), "capture-" + order.getId());

        confirmation.confirm(order, result.captureId());
        faults.failDbWriteIfRequested();
        return order;
    }
}
