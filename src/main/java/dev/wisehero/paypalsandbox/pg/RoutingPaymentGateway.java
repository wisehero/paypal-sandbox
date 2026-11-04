package dev.wisehero.paypalsandbox.pg;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문마다 PG를 고를 수 있게 하는 라우터.
 * 생성 이후에는 PG 주문 ID 접두어로 구분한다. 접두어는 각 PG 구현체가 {@link PgClient#orderIdPrefix()}로 알려 준다.
 */
public class RoutingPaymentGateway implements PaymentGateway {

    private final Map<PgProvider, PgClient> clients = new EnumMap<>(PgProvider.class);
    private final PgProvider defaultProvider;
    /** 환불할 때 캡처 ID만으로 PG를 찾기 위해 기억해 둔다. */
    private final Map<String, PgProvider> providerByCapture = new ConcurrentHashMap<>();

    public RoutingPaymentGateway(List<PgClient> clients, PgProvider defaultProvider) {
        clients.forEach(c -> this.clients.put(c.provider(), c));
        if (!this.clients.containsKey(defaultProvider)) {
            throw new IllegalStateException("기본 PG " + defaultProvider + "가 설정되지 않았다. 쓸 수 있는 PG: " + this.clients.keySet());
        }
        this.defaultProvider = defaultProvider;
    }

    public PgProvider defaultProvider() {
        return defaultProvider;
    }

    public List<PgProvider> availableProviders() {
        return List.copyOf(clients.keySet());
    }

    public PgOrder createOrder(PgProvider provider, String merchantOrderId, BigDecimal amount, String currency) {
        return client(provider).createOrder(merchantOrderId, amount, currency);
    }

    @Override
    public PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency) {
        return createOrder(defaultProvider, merchantOrderId, amount, currency);
    }

    @Override
    public CaptureResult capture(String pgOrderId, String idempotencyKey) {
        PgProvider provider = providerOfOrder(pgOrderId);
        CaptureResult result = client(provider).capture(pgOrderId, idempotencyKey);
        providerByCapture.put(result.captureId(), provider);
        return result;
    }

    @Override
    public PgOrderSnapshot getOrder(String pgOrderId) {
        PgProvider provider = providerOfOrder(pgOrderId);
        PgOrderSnapshot snapshot = client(provider).getOrder(pgOrderId);
        if (snapshot.captureId() != null) {
            providerByCapture.put(snapshot.captureId(), provider);
        }
        return snapshot;
    }

    @Override
    public void refund(String captureId, String idempotencyKey) {
        PgProvider provider = providerByCapture.get(captureId);
        if (provider == null) {
            throw new IllegalStateException("어느 PG의 캡처인지 모른다. 먼저 PG 주문을 조회한다: " + captureId);
        }
        client(provider).refund(captureId, idempotencyKey);
    }

    /** 접두어가 맞는 PG를 먼저 찾고, 없으면 접두어 없이 ID를 쓰는 PG(PayPal)로 본다. */
    private PgProvider providerOfOrder(String pgOrderId) {
        return clients.values().stream()
                .filter(c -> c.orderIdPrefix() != null && pgOrderId.startsWith(c.orderIdPrefix()))
                .findFirst()
                .or(() -> clients.values().stream().filter(c -> c.orderIdPrefix() == null).findFirst())
                .map(PgClient::provider)
                .orElseThrow(() -> new IllegalStateException("어느 PG의 주문인지 모른다: " + pgOrderId));
    }

    private PgClient client(PgProvider provider) {
        PgClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalStateException(provider + " PG가 설정되지 않았다. 쓸 수 있는 PG: " + clients.keySet());
        }
        return client;
    }
}
