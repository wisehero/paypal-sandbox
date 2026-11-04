package dev.wisehero.paypalsandbox.pg;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문마다 가짜 PG와 PayPal 중 하나를 고를 수 있게 하는 라우터.
 * 생성 이후에는 ID 접두어로 구분한다. 가짜 PG의 주문 ID는 FAKE-, 캡처 ID는 CAP-로 시작한다.
 */
public class RoutingPaymentGateway implements PaymentGateway {

    private final FakePaymentGateway fake;
    private final PayPalPaymentGateway paypal;
    private final PgProvider defaultProvider;

    public RoutingPaymentGateway(FakePaymentGateway fake, PayPalPaymentGateway paypal, PgProvider defaultProvider) {
        if (defaultProvider == PgProvider.PAYPAL && paypal == null) {
            throw new IllegalStateException("PG_PROVIDER=paypal이면 PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET이 필요하다");
        }
        this.fake = fake;
        this.paypal = paypal;
        this.defaultProvider = defaultProvider;
    }

    public PgProvider defaultProvider() {
        return defaultProvider;
    }

    public List<PgProvider> availableProviders() {
        List<PgProvider> providers = new ArrayList<>(List.of(PgProvider.FAKE));
        if (paypal != null) {
            providers.add(PgProvider.PAYPAL);
        }
        return providers;
    }

    public PgOrder createOrder(PgProvider provider, String merchantOrderId, BigDecimal amount, String currency) {
        return gateway(provider).createOrder(merchantOrderId, amount, currency);
    }

    @Override
    public PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency) {
        return createOrder(defaultProvider, merchantOrderId, amount, currency);
    }

    @Override
    public CaptureResult capture(String pgOrderId, String idempotencyKey) {
        return gateway(providerOfOrder(pgOrderId)).capture(pgOrderId, idempotencyKey);
    }

    @Override
    public PgOrderSnapshot getOrder(String pgOrderId) {
        return gateway(providerOfOrder(pgOrderId)).getOrder(pgOrderId);
    }

    @Override
    public void refund(String captureId, String idempotencyKey) {
        PgProvider provider = captureId.startsWith("CAP-") ? PgProvider.FAKE : PgProvider.PAYPAL;
        gateway(provider).refund(captureId, idempotencyKey);
    }

    public static PgProvider providerOfOrder(String pgOrderId) {
        return pgOrderId.startsWith("FAKE-") ? PgProvider.FAKE : PgProvider.PAYPAL;
    }

    private PaymentGateway gateway(PgProvider provider) {
        if (provider == PgProvider.FAKE) {
            return fake;
        }
        if (paypal == null) {
            throw new IllegalStateException("PayPal 키가 설정되지 않았다. .env에 PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET을 넣고 다시 실행한다");
        }
        return paypal;
    }
}
