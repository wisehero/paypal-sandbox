package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;

/**
 * 자동 테스트 전용 가짜 PG. 메모리 안에서 돈다.
 * 구매자 승인 단계를 생략해서 주문을 만들면 바로 APPROVED 상태가 된다.
 * 장애 주입으로 지연, 결제 후 타임아웃, 거절을 흉내 낸다.
 */
@RequiredArgsConstructor
public class FakePaymentGateway implements PgClient {

    private final FaultInjector faults;
    private final Map<String, FakeOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, String> captureByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public PgProvider provider() {
        return PgProvider.FAKE;
    }

    @Override
    public PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency) {
        String pgOrderId = "FAKE-" + UUID.randomUUID();
        orders.put(pgOrderId, new FakeOrder(amount, currency));
        return new PgOrder(pgOrderId, null);
    }

    @Override
    public CaptureResult capture(String pgOrderId, String idempotencyKey) {
        faults.delayPgCall();
        FakeOrder order = find(pgOrderId);

        String captureId;
        synchronized (order) {
            String previous = captureByIdempotencyKey.get(idempotencyKey);
            if (previous != null) {
                return new CaptureResult(previous);
            }
            if (faults.shouldDeclinePg()) {
                throw new PgDeclinedException("주입한 장애: PG 결제 거절");
            }
            if (order.captureId != null) {
                throw new PgDeclinedException("이미 다른 요청으로 캡처된 주문: " + pgOrderId);
            }
            captureId = "CAP-" + UUID.randomUUID();
            order.captureId = captureId;
            captureByIdempotencyKey.put(idempotencyKey, captureId);
        }

        if (faults.shouldTimeoutAfterCapture()) {
            // PG에서는 돈이 빠져나갔지만 응답이 우리에게 오지 않은 상황
            throw new PgTimeoutException("주입한 장애: PG 결제 후 응답 타임아웃");
        }
        return new CaptureResult(captureId);
    }

    @Override
    public PgOrderSnapshot getOrder(String pgOrderId) {
        FakeOrder order = find(pgOrderId);
        synchronized (order) {
            if (order.captureId != null) {
                return new PgOrderSnapshot(PgOrderStatus.COMPLETED, order.captureId);
            }
            return new PgOrderSnapshot(PgOrderStatus.APPROVED, null);
        }
    }

    @Override
    public void refund(String captureId, String idempotencyKey) {
        orders.values().stream()
                .filter(order -> captureId.equals(order.captureId))
                .findFirst()
                .orElseThrow(() -> new PgDeclinedException("없는 캡처: " + captureId))
                .refunded = true;
    }

    public boolean isRefunded(String pgOrderId) {
        return find(pgOrderId).refunded;
    }

    private FakeOrder find(String pgOrderId) {
        FakeOrder order = orders.get(pgOrderId);
        if (order == null) {
            throw new PgDeclinedException("없는 PG 주문: " + pgOrderId);
        }
        return order;
    }

    private static final class FakeOrder {

        private final BigDecimal amount;
        private final String currency;
        private volatile String captureId;
        private volatile boolean refunded;

        private FakeOrder(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }
}
