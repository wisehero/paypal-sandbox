package dev.wisehero.paypalsandbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트마다 MySQL 컨테이너를 띄운다. 운영과 같은 DB에서 행 락과 커넥션 동작을 본다.
 * 스프링 컨텍스트마다 컨테이너가 따로 떠서 테스트끼리 데이터가 섞이지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4");
    }
}
