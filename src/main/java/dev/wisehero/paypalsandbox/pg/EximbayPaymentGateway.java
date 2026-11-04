package dev.wisehero.paypalsandbox.pg;

import dev.wisehero.paypalsandbox.fault.FaultInjector;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 엑심베이 인증 결제창(PAYER_AUTH) 연동.
 *
 * <p>흐름은 PayPal과 같은 모양으로 맞췄다.
 * <ol>
 *   <li>주문 생성: 결제 준비 API로 fgkey를 받는다. (PayPal의 주문 생성)</li>
 *   <li>구매자가 결제창에서 카드 인증을 마치면 return_url, status_url로 결과가 온다.
 *       결과를 결제 검증 API로 검증하고 인증 거래 ID를 기억한다. (PayPal의 구매자 승인)</li>
 *   <li>캡처: 결제 승인 API(PAYMENT_PA)를 부른다. 이때 실제로 돈이 빠져나간다. (PayPal의 캡처)</li>
 * </ol>
 *
 * <p>결제창에 넘길 파라미터와 인증 결과는 메모리에만 둔다. 재시작하면 사라진다.
 */
public class EximbayPaymentGateway implements PgClient {

    /** 엑심베이 테스트 가맹점은 여러 사람이 함께 쓰므로 주문 ID가 겹치지 않게 시각과 난수를 붙인다. */
    public static final String ORDER_PREFIX = "SBX";

    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EximbayProperties properties;
    private final FaultInjector faults;
    private final RestClient client;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> orderByTransaction = new ConcurrentHashMap<>();

    public EximbayPaymentGateway(EximbayProperties properties, FaultInjector faults) {
        this.properties = properties;
        this.faults = faults;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        String basic = Base64.getEncoder()
                .encodeToString((properties.apiKey() + ":").getBytes(StandardCharsets.UTF_8));
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    @Override
    public PgProvider provider() {
        return PgProvider.EXIMBAY;
    }

    @Override
    public PgOrder createOrder(String merchantOrderId, BigDecimal amount, String currency) {
        String pgOrderId = ORDER_PREFIX + LocalDateTime.now().format(ORDER_TIME)
                + UUID.randomUUID().toString().substring(0, 6);
        String amountText = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("payment", Map.of(
                "transaction_type", "PAYER_AUTH",
                "order_id", pgOrderId,
                "currency", currency,
                "amount", amountText,
                "lang", properties.lang()));
        request.put("merchant", Map.of("mid", properties.mid()));
        request.put("buyer", Map.of("name", "Sandbox Buyer", "email", "buyer@example.com"));
        request.put("url", Map.of("return_url", properties.returnUrl(), "status_url", properties.statusUrl()));
        request.put("other_param", Map.of("param1", merchantOrderId));

        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/payments/ready")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Json.MAP_TYPE));
        requireSuccess(response, "결제 준비");

        sessions.put(pgOrderId, new Session(pgOrderId, amountText, currency, Json.string(response, "fgkey"), request));
        return new PgOrder(pgOrderId, "/eximbay-pay.html?token=" + pgOrderId);
    }

    /** 결제창을 띄울 때 SDK에 넘길 값. 결제 준비 API에 보낸 값과 똑같아야 fgkey가 맞는다. */
    public SdkParams sdkParams(String pgOrderId) {
        Session session = session(pgOrderId);
        Map<String, Object> params = new LinkedHashMap<>(session.getReadyRequest());
        params.put("fgkey", session.getFgkey());
        // 결제창을 팝업 대신 현재 화면 이동으로 띄운다. fgkey 계산에는 들어가지 않는다.
        params.put("settings", Map.of("display_type", "R", "autoclose", "Y"));
        return new SdkParams(properties.baseUrl() + "/v3/javascriptSDK.js", params,
                session.getAmount(), session.getCurrency());
    }

    /**
     * return_url이나 status_url로 받은 결과를 검증한다.
     * 받은 파라미터를 하나도 바꾸지 않고 결제 검증 API에 그대로 보낸다.
     */
    public AuthResult handleAuthResult(Map<String, String> params) {
        String pgOrderId = params.get("order_id");
        String rescode = params.get("rescode");
        if (pgOrderId == null) {
            return new AuthResult(null, false, "엑심베이 결과에 order_id가 없다");
        }
        if (!"0000".equals(rescode)) {
            return new AuthResult(pgOrderId, false, "엑심베이 인증 실패: " + rescode + " " + params.get("resmsg"));
        }

        StringBuilder data = new StringBuilder();
        params.forEach((k, v) -> data.append(data.isEmpty() ? "" : "&").append(k).append('=').append(v));
        Map<String, Object> verify = call(() -> client.post()
                .uri("/v1/payments/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", data.toString()))
                .retrieve()
                .body(Json.MAP_TYPE));
        if (!"0000".equals(Json.string(verify, "rescode"))) {
            return new AuthResult(pgOrderId, false, "결제 검증 실패: " + Json.string(verify, "resmsg"));
        }

        Session session = sessions.get(pgOrderId);
        if (session == null) {
            return new AuthResult(pgOrderId, false, "모르는 엑심베이 주문. 서버를 재시작하면 결제 준비 정보가 사라진다: " + pgOrderId);
        }
        session.setAuthTransactionId(params.get("transaction_id"));
        return new AuthResult(pgOrderId, true, null);
    }

    @Override
    public CaptureResult capture(String pgOrderId, String idempotencyKey) {
        faults.delayPgCall();
        if (faults.shouldDeclinePg()) {
            throw new PgDeclinedException("주입한 장애: PG 결제 거절");
        }
        Session session = session(pgOrderId);
        if (session.getAuthTransactionId() == null) {
            throw new PgDeclinedException("구매자가 엑심베이 결제창에서 인증을 마치지 않았다: " + pgOrderId);
        }

        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "transaction_type", "PAYMENT_PA",
                        "mid", properties.mid(),
                        "payment", Map.of(
                                "order_id", pgOrderId,
                                "currency", session.getCurrency(),
                                "amount", session.getAmount(),
                                "payer_auth_id", session.getAuthTransactionId(),
                                "lang", properties.lang())))
                .retrieve()
                .body(Json.MAP_TYPE));

        String transactionId;
        if ("0000".equals(Json.string(response, "rescode"))) {
            transactionId = firstNonNull(Json.string(response, "transaction_id"),
                    Json.string(Json.asMap(response.get("payment")), "transaction_id"));
            if (transactionId == null) {
                transactionId = getOrder(pgOrderId).captureId();
            }
        } else {
            // 이미 승인된 주문이면 실패가 아니다. 조회해서 승인 거래 ID를 돌려준다. (재시도 때 같은 결과를 주기 위함)
            PgOrderSnapshot snapshot = getOrder(pgOrderId);
            if (!snapshot.isCaptured()) {
                throw new PgDeclinedException("엑심베이 승인 실패: " + Json.string(response, "rescode") + " "
                        + Json.string(response, "resmsg"));
            }
            transactionId = snapshot.captureId();
        }
        if (transactionId == null) {
            throw new PgTimeoutException("엑심베이 승인 응답에 거래 ID가 없다. 조회로 확인이 필요하다: " + pgOrderId);
        }
        orderByTransaction.put(transactionId, pgOrderId);

        if (faults.shouldTimeoutAfterCapture()) {
            throw new PgTimeoutException("주입한 장애: PG 결제 후 응답 타임아웃");
        }
        return new CaptureResult(transactionId);
    }

    /**
     * 결제 조회 API로 주문의 최종 상태를 본다.
     * 엑심베이는 결제가 없는 주문도 일시 오류 코드로 답할 때가 있어서, 조회 실패를 "결제 없음"으로 단정하지 않는다.
     */
    @Override
    public PgOrderSnapshot getOrder(String pgOrderId) {
        Session session = session(pgOrderId);
        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/payments/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "mid", properties.mid(),
                        "key_field", "order_id",
                        "payment", Map.of(
                                "order_id", pgOrderId,
                                "currency", session.getCurrency(),
                                "amount", session.getAmount(),
                                "lang", properties.lang())))
                .retrieve()
                .body(Json.MAP_TYPE));

        if (!"0000".equals(Json.string(response, "rescode"))) {
            // 인증조차 안 된 주문은 승인됐을 수 없다.
            return new PgOrderSnapshot(session.getAuthTransactionId() == null ? PgOrderStatus.CREATED : PgOrderStatus.UNKNOWN, null);
        }
        Map<String, Object> payment = Json.asMap(response.get("payment"));
        String status = firstNonNull(Json.string(payment, "status"), Json.string(response, "status"));
        String transactionId = firstNonNull(Json.string(payment, "transaction_id"), Json.string(response, "transaction_id"));
        if (transactionId != null) {
            orderByTransaction.put(transactionId, pgOrderId);
        }
        PgOrderStatus mapped = switch (status == null ? "" : status.toUpperCase()) {
            case "SALE", "AUTH", "CAPTURE" -> PgOrderStatus.COMPLETED;
            case "REFUND", "CANCEL", "VOID", "PARTIAL_REFUND" -> PgOrderStatus.VOIDED;
            default -> session.getAuthTransactionId() == null ? PgOrderStatus.CREATED : PgOrderStatus.APPROVED;
        };
        return new PgOrderSnapshot(mapped, mapped == PgOrderStatus.COMPLETED ? transactionId : null);
    }

    @Override
    public void refund(String captureId, String idempotencyKey) {
        String pgOrderId = orderByTransaction.get(captureId);
        if (pgOrderId == null) {
            throw new PgDeclinedException("모르는 엑심베이 거래: " + captureId);
        }
        Session session = session(pgOrderId);
        Map<String, Object> response = call(() -> client.post()
                .uri("/v1/payments/{transactionId}/cancel", captureId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "mid", properties.mid(),
                        "refund", Map.of(
                                "refund_type", "F",
                                "refund_amount", session.getAmount(),
                                "refund_id", idempotencyKey + "-" + captureId,
                                "reason", "order could not be completed"),
                        "payment", Map.of(
                                "order_id", pgOrderId,
                                "currency", session.getCurrency(),
                                "amount", session.getAmount(),
                                "balance", session.getAmount(),
                                "lang", properties.lang())))
                .retrieve()
                .body(Json.MAP_TYPE));
        requireSuccess(response, "결제 취소");
    }

    private Session session(String pgOrderId) {
        Session session = sessions.get(pgOrderId);
        if (session == null) {
            throw new PgDeclinedException("모르는 엑심베이 주문. 서버를 재시작하면 결제 준비 정보가 사라진다: " + pgOrderId);
        }
        return session;
    }

    private static void requireSuccess(Map<String, Object> response, String step) {
        if (!"0000".equals(Json.string(response, "rescode"))) {
            throw new PgDeclinedException("엑심베이 " + step + " 실패: " + Json.string(response, "rescode") + " "
                    + Json.string(response, "resmsg"));
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** 네트워크 오류와 5xx는 "결과를 모름", 4xx는 "확실한 거절"로 바꾼다. */
    private static Map<String, Object> call(Supplier<Map<String, Object>> request) {
        try {
            Map<String, Object> body = request.get();
            return body == null ? Map.of() : body;
        } catch (ResourceAccessException e) {
            throw new PgTimeoutException("엑심베이 응답 없음: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new PgTimeoutException("엑심베이 5xx: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            }
            throw new PgDeclinedException("엑심베이 " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static final class Session {

        private final String pgOrderId;
        private final String amount;
        private final String currency;
        private final String fgkey;
        private final Map<String, Object> readyRequest;
        /** 구매자 인증이 끝나면 채운다. return_url과 status_url이 서로 다른 스레드에서 올 수 있다. */
        @Setter
        private volatile String authTransactionId;
    }

    public record SdkParams(String sdkUrl, Map<String, Object> params, String amount, String currency) {
    }

    public record AuthResult(String pgOrderId, boolean success, String message) {
    }
}
