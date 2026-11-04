package dev.wisehero.paypalsandbox.pg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pg.paypal")
public record PayPalProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String webhookId,
        String returnUrl,
        String cancelUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}
