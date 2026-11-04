package dev.wisehero.paypalsandbox.web;

import com.zaxxer.hikari.HikariDataSource;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import dev.wisehero.paypalsandbox.pg.RoutingPaymentGateway;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화면이 처음 열릴 때 읽는 설정값. */
@RestController
class ConfigController {

    private final RoutingPaymentGateway gateway;
    private final HikariDataSource dataSource;
    private final Duration staleAfter;

    ConfigController(RoutingPaymentGateway gateway, DataSource dataSource,
            @Value("${reconcile.stale-after}") Duration staleAfter) {
        this.gateway = gateway;
        this.dataSource = (HikariDataSource) dataSource;
        this.staleAfter = staleAfter;
    }

    @GetMapping("/config")
    Config config() {
        return new Config(gateway.defaultProvider(), gateway.availableProviders(),
                dataSource.getMaximumPoolSize(), dataSource.getConnectionTimeout(), staleAfter.toSeconds());
    }

    record Config(PgProvider defaultPg, List<PgProvider> availablePgs, int poolSize, long connectionTimeoutMs,
            long reconcileStaleAfterSeconds) {
    }
}
