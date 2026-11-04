package dev.wisehero.paypalsandbox.pg;

import java.math.BigDecimal;

/**
 * PG 연동 경계. 구현체는 PayPal 샌드박스와 로컬 가짜 PG 두 가지다.
 *
 * <p>예외는 두 종류로 나눈다.
 * {@link PgDeclinedException}은 결제가 안 된 것이 확실할 때,
 * {@link PgTimeoutException}은 결제가 됐는지 모를 때 던진다.
 * 두 경우를 섞으면 결제된 주문을 실패로 처리하는 사고가 난다.
 */
public interface PaymentGateway {

    PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency);

    /** 같은 idempotencyKey로 다시 부르면 PG는 새로 결제하지 않고 처음 결과를 돌려줘야 한다. */
    CaptureResult capture(String pgOrderId, String idempotencyKey);

    PgOrderSnapshot getOrder(String pgOrderId);

    void refund(String captureId, String idempotencyKey);

    record PgOrder(String pgOrderId, String approveUrl) {
    }

    record CaptureResult(String captureId) {
    }

    record PgOrderSnapshot(PgOrderStatus status, String captureId) {

        public boolean isCaptured() {
            return status == PgOrderStatus.COMPLETED && captureId != null;
        }
    }

    enum PgOrderStatus {
        CREATED,
        APPROVED,
        COMPLETED,
        VOIDED,
        UNKNOWN
    }
}
