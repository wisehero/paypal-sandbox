package dev.wisehero.paypalsandbox.pg;

/** PG가 결제를 거절했다. 돈이 빠져나가지 않은 것이 확실하다. */
public class PgDeclinedException extends RuntimeException {

    public PgDeclinedException(String message) {
        super(message);
    }
}
