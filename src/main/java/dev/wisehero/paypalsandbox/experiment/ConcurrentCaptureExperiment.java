package dev.wisehero.paypalsandbox.experiment;

import dev.wisehero.paypalsandbox.checkout.OrderService;
import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * 같은 상품을 가짜 PG로 동시에 여러 건 캡처한다.
 * 화면은 실행 중에 커넥션 풀 상태와 상관없는 조회의 성공 여부를 같이 지켜본다.
 */
@Service
public class ConcurrentCaptureExperiment {

    private final OrderService orderService;
    private final FaultInjector faults;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    ConcurrentCaptureExperiment(OrderService orderService, FaultInjector faults) {
        this.orderService = orderService;
        this.faults = faults;
    }

    public Run start(Long productId, CaptureStrategy strategy, int count, Duration pgDelay) {
        List<Long> orderIds = IntStream.range(0, count)
                .mapToObj(i -> orderService.create(productId, 1, PgProvider.FAKE).orderId())
                .toList();

        FaultInjector.Settings previous = faults.current();
        faults.apply(previous.withPgDelay(pgDelay));

        Run run = new Run(UUID.randomUUID().toString(), strategy, count, pgDelay.toMillis(), Instant.now(),
                new ConcurrentHashMap<>());
        orderIds.forEach(id -> run.outcomes().put(id, Outcome.running()));
        runs.put(run.id(), run);

        AtomicInteger remaining = new AtomicInteger(count);
        for (Long orderId : orderIds) {
            executor.submit(() -> {
                long started = System.nanoTime();
                try {
                    String status = orderService.capture(orderId, strategy).getStatus().name();
                    run.outcomes().put(orderId, Outcome.done(status, null, started));
                } catch (RuntimeException e) {
                    run.outcomes().put(orderId, Outcome.done("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), started));
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        // 실험 전에 걸려 있던 PG 지연 설정으로 되돌린다.
                        faults.apply(faults.current().withPgDelay(Duration.ofMillis(previous.pgDelayMs())));
                    }
                }
            });
        }
        return run;
    }

    public Run get(String id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new IllegalArgumentException("없는 실험: " + id);
        }
        return run;
    }

    public record Run(String id, CaptureStrategy strategy, int count, long pgDelayMs, Instant startedAt,
            Map<Long, Outcome> outcomes) {

        public boolean finished() {
            return outcomes.values().stream().noneMatch(o -> o.result().equals("RUNNING"));
        }
    }

    public record Outcome(String result, String error, Long elapsedMs) {

        static Outcome running() {
            return new Outcome("RUNNING", null, null);
        }

        static Outcome done(String result, String error, long startedNanos) {
            return new Outcome(result, error, (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }
}
