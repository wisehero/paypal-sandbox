package dev.wisehero.paypalsandbox.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reconcile.schedule-enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
class ReconcileScheduler {

    private final PaymentReconciler reconciler;

    @Scheduled(fixedDelayString = "${reconcile.fixed-delay}")
    void run() {
        reconciler.reconcileStale().forEach(result -> log.info("대사 결과: {}", result));
    }
}
