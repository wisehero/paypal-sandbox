package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * PayPal Orders v2 API 샌드박스 연동.
 *
 * <p>흐름: 주문 생성(create) → 구매자가 approveUrl에서 승인 → 서버가 캡처(capture).
 * 캡처 요청에는 PayPal-Request-Id 헤더로 멱등 키를 넘긴다. 같은 키로 다시 요청하면
 * PayPal은 새로 결제하지 않고 처음 응답을 돌려준다.
 */
public class PayPalPaymentGateway implements PgClient {

    private final PayPalProperties properties;
    private final FaultInjector faults;
    private final RestClient client;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public PayPalPaymentGateway(PayPalProperties properties, FaultInjector faults) {
        if (!StringUtils.hasText(properties.clientId()) || !StringUtils.hasText(properties.clientSecret())) {
            throw new IllegalStateException("PG_PROVIDER=paypal이면 PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET이 필요하다");
        }
        this.properties = properties;
        this.faults = faults;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PgProvider provider() {
        return PgProvider.PAYPAL;
    }

    @Override
    public PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency) {
        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", merchantOrderId,
                        "custom_id", merchantOrderId,
                        "amount", Map.of("currency_code", currency, "value", amount.toPlainString()))),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "return_url", properties.returnUrl(),
                        "cancel_url", properties.cancelUrl(),
                        "user_action", "PAY_NOW"))));

        Map<String, Object> response = call(() -> client.post()
                .uri("/v2/checkout/orders")
                .headers(h -> h.setBearerAuth(token()))
                .header("PayPal-Request-Id", "create-" + merchantOrderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Json.MAP_TYPE));

        String approveUrl = Json.list(response, "links").stream()
                .map(Json::asMap)
                .filter(link -> "payer-action".equals(link.get("rel")) || "approve".equals(link.get("rel")))
                .map(link -> (String) link.get("href"))
                .findFirst()
                .orElse(null);
        return new PgOrder(Json.string(response, "id"), approveUrl);
    }

    @Override
    public CaptureResult capture(String pgOrderId, String idempotencyKey) {
        faults.delayPgCall();
        if (faults.shouldDeclinePg()) {
            throw new PgDeclinedException("주입한 장애: PG 결제 거절");
        }

        Map<String, Object> response;
        try {
            response = call(() -> client.post()
                    .uri("/v2/checkout/orders/{id}/capture", pgOrderId)
                    .headers(h -> h.setBearerAuth(token()))
                    .header("PayPal-Request-Id", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(Json.MAP_TYPE));
        } catch (PgDeclinedException e) {
            // 이미 캡처된 주문이면 실패가 아니다. 현재 상태를 조회해서 캡처 ID를 돌려준다.
            if (e.getMessage().contains("ORDER_ALREADY_CAPTURED")) {
                PgOrderSnapshot snapshot = getOrder(pgOrderId);
                if (snapshot.isCaptured()) {
                    return new CaptureResult(snapshot.captureId());
                }
            }
            throw e;
        }

        Map<String, Object> capture = firstCapture(response);
        String captureStatus = capture == null ? null : (String) capture.get("status");
        if (capture == null || !("COMPLETED".equals(captureStatus) || "PENDING".equals(captureStatus))) {
            throw new PgDeclinedException("캡처 실패: status=" + captureStatus + ", order=" + pgOrderId);
        }

        if (faults.shouldTimeoutAfterCapture()) {
            throw new PgTimeoutException("주입한 장애: PG 결제 후 응답 타임아웃");
        }
        return new CaptureResult((String) capture.get("id"));
    }

    @Override
    public PgOrderSnapshot getOrder(String pgOrderId) {
        Map<String, Object> response = call(() -> client.get()
                .uri("/v2/checkout/orders/{id}", pgOrderId)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .body(Json.MAP_TYPE));

        PgOrderStatus status = switch (Json.string(response, "status")) {
            case "CREATED", "SAVED", "PAYER_ACTION_REQUIRED" -> PgOrderStatus.CREATED;
            case "APPROVED" -> PgOrderStatus.APPROVED;
            case "COMPLETED" -> PgOrderStatus.COMPLETED;
            case "VOIDED" -> PgOrderStatus.VOIDED;
            case null, default -> PgOrderStatus.UNKNOWN;
        };
        Map<String, Object> capture = firstCapture(response);
        return new PgOrderSnapshot(status, capture == null ? null : (String) capture.get("id"));
    }

    @Override
    public void refund(String captureId, String idempotencyKey) {
        call(() -> client.post()
                .uri("/v2/payments/captures/{id}/refund", captureId)
                .headers(h -> h.setBearerAuth(token()))
                .header("PayPal-Request-Id", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(Json.MAP_TYPE));
    }

    /** 웹훅 서명을 PayPal API로 검증한다. webhook-id를 설정하지 않았으면 검증하지 않는다. */
    public boolean verifyWebhook(Map<String, String> headers, Map<String, Object> event) {
        if (!StringUtils.hasText(properties.webhookId())) {
            return true;
        }
        Map<String, Object> body = Map.of(
                "auth_algo", headers.getOrDefault("paypal-auth-algo", ""),
                "cert_url", headers.getOrDefault("paypal-cert-url", ""),
                "transmission_id", headers.getOrDefault("paypal-transmission-id", ""),
                "transmission_sig", headers.getOrDefault("paypal-transmission-sig", ""),
                "transmission_time", headers.getOrDefault("paypal-transmission-time", ""),
                "webhook_id", properties.webhookId(),
                "webhook_event", event);
        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/notifications/verify-webhook-signature")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Json.MAP_TYPE));
        return "SUCCESS".equals(response.get("verification_status"));
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(60))) {
            return accessToken;
        }
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(Json.MAP_TYPE));
        accessToken = Json.string(response, "access_token");
        Number expiresIn = (Number) response.get("expires_in");
        accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn == null ? 300 : expiresIn.longValue());
        return accessToken;
    }

    private static Map<String, Object> firstCapture(Map<String, Object> order) {
        List<Object> units = Json.list(order, "purchase_units");
        if (units.isEmpty()) {
            return null;
        }
        Map<String, Object> payments = Json.asMap(Json.asMap(units.getFirst()).get("payments"));
        List<Object> captures = Json.list(payments, "captures");
        return captures.isEmpty() ? null : Json.asMap(captures.getFirst());
    }

    /**
     * 네트워크 오류와 5xx는 "결과를 모름"으로, 4xx는 "확실한 거절"로 바꾼다.
     * 5xx는 PayPal 내부에서 처리가 됐을 수도 있으므로 거절로 단정하지 않는다.
     */
    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new PgTimeoutException("PayPal 응답 없음: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is5xxServerError()) {
                throw new PgTimeoutException("PayPal 5xx: " + status + " " + e.getResponseBodyAsString(), e);
            }
            throw new PgDeclinedException("PayPal " + status + ": " + e.getResponseBodyAsString());
        }
    }
}
