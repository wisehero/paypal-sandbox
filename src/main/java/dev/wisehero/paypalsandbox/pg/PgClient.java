package dev.wisehero.paypalsandbox.pg;

/** 실제 PG 하나와 연결되는 구현체. {@link RoutingPaymentGateway}가 주문마다 골라 쓴다. */
public interface PgClient extends PaymentGateway {

    PgProvider provider();
}
