package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PayPalProperties.class)
class PgConfig {

    @Bean
    @ConditionalOnProperty(name = "pg.provider", havingValue = "fake", matchIfMissing = true)
    FakePaymentGateway fakePaymentGateway(FaultInjector faults) {
        return new FakePaymentGateway(faults);
    }

    @Bean
    @ConditionalOnProperty(name = "pg.provider", havingValue = "paypal")
    PayPalPaymentGateway payPalPaymentGateway(PayPalProperties properties, FaultInjector faults) {
        return new PayPalPaymentGateway(properties, faults);
    }
}
