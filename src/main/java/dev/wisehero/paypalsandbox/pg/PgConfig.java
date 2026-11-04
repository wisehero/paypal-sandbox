package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(PayPalProperties.class)
class PgConfig {

    /** PayPal 키가 있으면 PayPal 연동을 켠다. */
    static final String PAYPAL_ENABLED = "!'${pg.paypal.client-id:}'.isEmpty()";

    @Bean
    FakePaymentGateway fakePaymentGateway(FaultInjector faults) {
        return new FakePaymentGateway(faults);
    }

    @Bean
    @ConditionalOnExpression(PAYPAL_ENABLED)
    PayPalPaymentGateway payPalPaymentGateway(PayPalProperties properties, FaultInjector faults) {
        return new PayPalPaymentGateway(properties, faults);
    }

    @Bean
    @Primary
    RoutingPaymentGateway routingPaymentGateway(FakePaymentGateway fake, ObjectProvider<PayPalPaymentGateway> paypal,
            @Value("${pg.provider}") String defaultProvider) {
        return new RoutingPaymentGateway(fake, paypal.getIfAvailable(), PgProvider.from(defaultProvider));
    }
}
