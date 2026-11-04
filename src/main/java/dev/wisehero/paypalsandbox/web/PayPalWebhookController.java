package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.checkout.PaymentReconciler;
import dev.wisehero.paypalsandbox.pg.PayPalPaymentGateway;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPal 웹훅. PAYMENT.CAPTURE.COMPLETED를 받으면 해당 주문을 대사한다.
 * 웹훅 내용을 그대로 믿지 않고 PG 주문을 다시 조회해서 판단한다.
 */
@RestController
@ConditionalOnExpression("!'${pg.paypal.client-id:}'.isEmpty()")
@Slf4j
@RequiredArgsConstructor
class PayPalWebhookController {

    private final PayPalPaymentGateway gateway;
    private final PaymentReconciler reconciler;

    @PostMapping("/webhooks/paypal")
    ResponseEntity<Void> receive(@RequestHeader Map<String, String> headers, @RequestBody Map<String, Object> event) {
        if (!gateway.verifyWebhook(headers, event)) {
            log.warn("웹훅 서명 검증 실패: id={}", event.get("id"));
            return ResponseEntity.badRequest().build();
        }
        if (!"PAYMENT.CAPTURE.COMPLETED".equals(event.get("event_type"))) {
            return ResponseEntity.ok().build();
        }
        String pgOrderId = relatedOrderId(event);
        if (pgOrderId == null) {
            log.warn("웹훅에 PayPal 주문 ID가 없음: id={}", event.get("id"));
            return ResponseEntity.ok().build();
        }
        log.info("웹훅 대사 결과: {}", reconciler.reconcileByPgOrderId(pgOrderId));
        return ResponseEntity.ok().build();
    }

    private static String relatedOrderId(Map<String, Object> event) {
        Object resource = event.get("resource");
        if (resource instanceof Map<?, ?> r
                && r.get("supplementary_data") instanceof Map<?, ?> s
                && s.get("related_ids") instanceof Map<?, ?> ids
                && ids.get("order_id") instanceof String orderId) {
            return orderId;
        }
        return null;
    }
}
