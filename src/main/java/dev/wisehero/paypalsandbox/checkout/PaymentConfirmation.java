package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.payment.OutboxEvent;
import dev.wisehero.paypalsandbox.payment.OutboxEventRepository;
import dev.wisehero.paypalsandbox.payment.PaymentRecord;
import dev.wisehero.paypalsandbox.payment.PaymentRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 확정: 주문을 PAID로 바꾸고 결제 기록과 후속 이벤트를 남긴다.
 * 캡처 직후 경로와 대사 경로가 같이 쓴다. 여러 번 불려도 결과가 같다.
 */
@Component
@RequiredArgsConstructor
public class PaymentConfirmation {

    private final PaymentRecordRepository payments;
    private final OutboxEventRepository outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(PurchaseOrder order, String captureId) {
        if (order.getStatus() == OrderStatus.PAID) {
            return;
        }
        order.markPaid(captureId);
        if (!payments.existsByCaptureId(captureId)) {
            payments.save(new PaymentRecord(
                    order.getId(), order.getPgOrderId(), captureId, order.getAmount(), order.getCurrency()));
        }
        outbox.save(new OutboxEvent("ORDER_PAID", order.getId()));
    }
}
