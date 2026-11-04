package dev.wisehero.paypalsandbox.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reconcile.schedule-enabled", havingValue = "true")
class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    private final PaymentReconciler reconciler;

    ReconcileScheduler(PaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${reconcile.fixed-delay}")
    void run() {
        reconciler.reconcileStale().forEach(result -> log.info("대사 결과: {}", result));
    }
}
