package dev.wisehero.paypalsandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(MySqlTestConfig.class)
class PaypalSandboxApplicationTests {

	@Test
	void contextLoads() {
	}

}
