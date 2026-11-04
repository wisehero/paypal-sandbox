package dev.wisehero.paypalsandbox.pg;

/** PG 응답을 받지 못했다. 결제가 됐을 수도 있고 안 됐을 수도 있다. */
public class PgTimeoutException extends RuntimeException {

    public PgTimeoutException(String message) {
        super(message);
    }

    public PgTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
