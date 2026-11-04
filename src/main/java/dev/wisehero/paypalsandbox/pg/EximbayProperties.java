package dev.wisehero.paypalsandbox.pg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.eximbay")
public record EximbayProperties(
        String baseUrl,
        String mid,
        String apiKey,
        String returnUrl,
        String statusUrl,
        String lang,
        Duration connectTimeout,
        Duration readTimeout) {
}
