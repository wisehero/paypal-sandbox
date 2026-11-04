package dev.wisehero.paypalsandbox.order;

public enum CaptureStrategy {
    /** PG 호출을 DB 트랜잭션 안에 넣은 구조 */
    IN_TX,
    /** PG 호출을 트랜잭션 밖으로 빼고 앞뒤를 짧은 트랜잭션 두 개로 나눈 구조 */
    SPLIT;

    public static CaptureStrategy from(String value) {
        return switch (value.toLowerCase()) {
            case "in-tx", "in_tx", "intx" -> IN_TX;
            case "split" -> SPLIT;
            default -> throw new IllegalArgumentException("strategy는 in-tx 또는 split: " + value);
        };
    }
}
