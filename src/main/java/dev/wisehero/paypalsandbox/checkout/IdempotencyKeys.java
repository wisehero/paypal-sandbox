package dev.wisehero.paypalsandbox.checkout;

/**
 * PG에 넘기는 멱등 키. 같은 주문의 재시도에는 항상 같은 키가 가야 하므로 주문 ID로만 만든다.
 * 캡처 직후 경로와 대사 경로가 서로 다른 키를 쓰면 PG가 두 번 결제할 수 있다.
 */
final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    static String capture(Long orderId) {
        return "capture-" + orderId;
    }

    static String refund(Long orderId) {
        return "refund-" + orderId;
    }
}
