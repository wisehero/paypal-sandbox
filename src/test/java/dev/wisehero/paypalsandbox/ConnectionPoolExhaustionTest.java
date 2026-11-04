package dev.wisehero.paypalsandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wisehero.paypalsandbox.checkout.OrderService;
import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.pg.FakePgConfig;
import dev.wisehero.paypalsandbox.product.Product;
import dev.wisehero.paypalsandbox.product.ProductRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커넥션 풀 2개, 커넥션 대기 300ms, PG 응답 1.5초.
 * 결제 2건이 동시에 PG를 기다리는 동안 결제와 상관없는 조회가 커넥션을 얻을 수 있는지 본다.
 */
@SpringBootTest(properties = {
        "pg.provider=fake",
        "spring.datasource.url=jdbc:h2:mem:pool-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=300"
})
@Import(FakePgConfig.class)
class ConnectionPoolExhaustionTest {

    private static final Duration PG_DELAY = Duration.ofMillis(1500);

    @Autowired
    OrderService orderService;
    @Autowired
    FaultInjector faults;
    @Autowired
    ProductRepository products;
    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    @Test
    @DisplayName("트랜잭션 안: PG를 기다리는 결제 2건이 커넥션 2개를 모두 쥐어서 상품 조회가 실패한다")
    void inTransactionStarvesPool() throws Exception {
        List<CompletableFuture<?>> captures = startConcurrentCaptures(CaptureStrategy.IN_TX);
        try {
            assertThatThrownBy(() -> tx.execute(s -> products.count()))
                    .isInstanceOf(CannotCreateTransactionException.class);
        } finally {
            CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).join();
        }
    }

    @Test
    @DisplayName("트랜잭션 분리: PG를 기다리는 동안 커넥션을 반납해서 상품 조회가 된다")
    void splitKeepsPoolAvailable() throws Exception {
        List<CompletableFuture<?>> captures = startConcurrentCaptures(CaptureStrategy.SPLIT);
        try {
            Long count = tx.execute(s -> products.count());
            assertThat(count).isPositive();
        } finally {
            CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).join();
        }
    }

    private List<CompletableFuture<?>> startConcurrentCaptures(CaptureStrategy strategy) throws InterruptedException {
        List<Long> orderIds = IntStream.range(0, 2)
                .mapToObj(i -> {
                    Product product = products.save(new Product("pool-" + i, new BigDecimal("1.00"), 10));
                    return orderService.create(product.getId(), 1).orderId();
                })
                .toList();
        faults.apply(FaultInjector.Settings.NONE.withPgDelay(PG_DELAY));

        List<CompletableFuture<?>> futures = orderIds.stream()
                .<CompletableFuture<?>>map(id -> CompletableFuture.runAsync(() ->
                        assertThat(orderService.capture(id, strategy).getStatus()).isEqualTo(OrderStatus.PAID)))
                .toList();
        // 두 결제가 PG 호출에 들어갈 때까지 기다린다.
        Thread.sleep(400);
        return futures;
    }
}
