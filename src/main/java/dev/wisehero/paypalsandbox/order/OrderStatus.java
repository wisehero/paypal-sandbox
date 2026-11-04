package dev.wisehero.paypalsandbox.order;

public enum OrderStatus {
    /** 주문 생성. 아직 결제 시도 전. */
    CREATED,
    /** 결제 시작을 기록했고 PG 응답을 기다리는 중 (트랜잭션 분리 구조에서만 쓴다). */
    PAYMENT_IN_PROGRESS,
    /** PG 호출이 타임아웃 나서 결제가 됐는지 모른다. 대사로 확정한다. */
    PAYMENT_UNKNOWN,
    PAID,
    PAYMENT_FAILED,
    /** PG에서는 결제가 됐지만 주문을 확정할 수 없어 환불했다. */
    REFUNDED
}
