package dev.wisehero.paypalsandbox.pg;

public enum PgProvider {
    FAKE,
    PAYPAL;

    public static PgProvider from(String value) {
        return switch (value.trim().toLowerCase()) {
            case "fake" -> FAKE;
            case "paypal" -> PAYPAL;
            default -> throw new IllegalArgumentException("PG는 fake 또는 paypal: " + value);
        };
    }
}
