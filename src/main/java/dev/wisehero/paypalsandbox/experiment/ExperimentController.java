package dev.wisehero.paypalsandbox.experiment;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import dev.wisehero.paypalsandbox.pg.RoutingPaymentGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExperimentController {

    private final ConcurrentCaptureExperiment experiment;
    private final HikariDataSource dataSource;
    private final RoutingPaymentGateway gateway;
    private final Duration staleAfter;

    ExperimentController(ConcurrentCaptureExperiment experiment, DataSource dataSource, RoutingPaymentGateway gateway,
            @Value("${reconcile.stale-after}") Duration staleAfter) {
        this.experiment = experiment;
        this.dataSource = (HikariDataSource) dataSource;
        this.gateway = gateway;
        this.staleAfter = staleAfter;
    }

    /** 화면 첫 로딩 때 읽는 설정값. */
    @GetMapping("/config")
    Config config() {
        return new Config(gateway.defaultProvider(), gateway.availableProviders(),
                dataSource.getMaximumPoolSize(), dataSource.getConnectionTimeout(), staleAfter.toSeconds());
    }

    /** 커넥션 풀 현재 상태. DB를 거치지 않으므로 풀이 고갈돼도 응답한다. */
    @GetMapping("/experiments/pool")
    Pool pool() {
        HikariPoolMXBean bean = dataSource.getHikariPoolMXBean();
        if (bean == null) {
            return new Pool(0, 0, dataSource.getMaximumPoolSize(), 0);
        }
        return new Pool(bean.getActiveConnections(), bean.getIdleConnections(), dataSource.getMaximumPoolSize(),
                bean.getThreadsAwaitingConnection());
    }

    @PostMapping("/experiments/concurrent-captures")
    ConcurrentCaptureExperiment.Run start(@Valid @RequestBody StartRequest request) {
        return experiment.start(request.productId(), CaptureStrategy.from(request.strategy()), request.count(),
                Duration.ofMillis(request.pgDelayMs()));
    }

    @GetMapping("/experiments/concurrent-captures/{id}")
    ConcurrentCaptureExperiment.Run get(@PathVariable String id) {
        return experiment.get(id);
    }

    record Config(PgProvider defaultPg, List<PgProvider> availablePgs, int poolSize, long connectionTimeoutMs,
            long reconcileStaleAfterSeconds) {
    }

    record Pool(int active, int idle, int max, int waiting) {
    }

    record StartRequest(@NotNull Long productId, @NotNull String strategy, @Min(1) @Max(20) int count,
            @Min(0) @Max(20000) long pgDelayMs) {
    }
}
