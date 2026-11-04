package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 테스트에서 가짜 PG를 등록한다. 외부 PG를 부르지 않고 장애를 정확히 재현할 수 있다. */
@TestConfiguration
public class FakePgConfig {

    @Bean
    FakePaymentGateway fakePaymentGateway(FaultInjector faults) {
        return new FakePaymentGateway(faults);
    }
}
