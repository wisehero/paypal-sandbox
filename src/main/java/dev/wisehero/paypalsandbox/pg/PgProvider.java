package dev.wisehero.paypalsandbox.pg;

public enum PgProvider {
    PAYPAL,
    EXIMBAY,
    /** 자동 테스트 전용 가짜 PG. 운영 코드에는 구현체가 없다. */
    FAKE;

    public static PgProvider from(String value) {
        return switch (value.trim().toLowerCase()) {
            case "paypal" -> PAYPAL;
            case "eximbay" -> EXIMBAY;
            case "fake" -> FAKE;
            default -> throw new IllegalArgumentException("PG는 paypal 또는 eximbay: " + value);
        };
    }
}
