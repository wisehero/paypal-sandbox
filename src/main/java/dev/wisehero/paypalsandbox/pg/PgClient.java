package dev.wisehero.paypalsandbox.pg;

/** 실제 PG 하나와 연결되는 구현체. {@link RoutingPaymentGateway}가 주문마다 골라 쓴다. */
public interface PgClient extends PaymentGateway {

    PgProvider provider();

    /**
     * 이 PG의 주문 ID가 시작하는 접두어. 라우터는 이 값으로 주문 ID가 어느 PG 것인지 가린다.
     * 주문 ID를 PG가 정해서 접두어가 없으면 null이고, 접두어로 가려지지 않는 주문 ID는 이 PG로 보낸다.
     */
    default String orderIdPrefix() {
        return null;
    }
}
