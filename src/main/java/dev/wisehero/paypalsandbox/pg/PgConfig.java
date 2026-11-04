package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties({PayPalProperties.class, EximbayProperties.class})
class PgConfig {

    /** PayPal 키가 있으면 PayPal 연동을 켠다. */
    @Bean
    @ConditionalOnExpression("!'${pg.paypal.client-id:}'.isEmpty()")
    PayPalPaymentGateway payPalPaymentGateway(PayPalProperties properties, FaultInjector faults) {
        return new PayPalPaymentGateway(properties, faults);
    }

    /** 엑심베이 API 키가 있으면 켠다. 기본값으로 공용 테스트 키가 들어 있다. */
    @Bean
    @ConditionalOnExpression("!'${pg.eximbay.api-key:}'.isEmpty()")
    EximbayPaymentGateway eximbayPaymentGateway(EximbayProperties properties, FaultInjector faults) {
        return new EximbayPaymentGateway(properties, faults);
    }

    @Bean
    @Primary
    RoutingPaymentGateway routingPaymentGateway(List<PgClient> clients, @Value("${pg.provider}") String defaultProvider) {
        return new RoutingPaymentGateway(clients, PgProvider.from(defaultProvider));
    }
}
