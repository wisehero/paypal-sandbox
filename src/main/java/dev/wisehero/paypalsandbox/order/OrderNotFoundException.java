package dev.wisehero.paypalsandbox.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Object key) {
        super("주문 없음: " + key);
    }
}
