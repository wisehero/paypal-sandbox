package dev.wisehero.paypalsandbox.fault;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 실험용 장애 주입 스위치. 한 번 켜면 끌 때까지 유지된다.
 *
 * <ul>
 *   <li>pgDelay: PG 호출마다 이만큼 늦게 응답한다.</li>
 *   <li>pgTimeoutAfterCapture: PG는 결제를 끝냈는데 우리 쪽은 응답을 못 받은 것처럼 만든다.</li>
 *   <li>dbFailureAfterCapture: PG 결제가 끝난 뒤 DB 반영 단계에서 예외를 던져 롤백시킨다.</li>
 *   <li>pgDecline: PG가 결제를 거절한다.</li>
 *   <li>paypalMockResponse: PayPal 캡처 요청에 PayPal-Mock-Response 헤더로 이 오류 코드를 붙인다.
 *       우리 코드가 흉내 내는 다른 장애와 달리 PayPal 샌드박스가 실제 오류 응답을 만들어 보낸다.</li>
 * </ul>
 */
@Component
public class FaultInjector {

    /** PayPal 오류 코드 모양. 헤더에 그대로 들어가므로 다른 문자는 받지 않는다. */
    private static final Pattern MOCK_CODE = Pattern.compile("[A-Z_]{1,64}");

    private volatile Settings settings = Settings.NONE;

    public Settings current() {
        return settings;
    }

    public void apply(Settings settings) {
        String code = settings.paypalMockResponse();
        if (code != null && !code.isBlank() && !MOCK_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("PayPal 모의 응답 코드는 대문자와 _만 쓸 수 있다: " + code);
        }
        this.settings = code != null && code.isBlank() ? settings.withPaypalMockResponse(null) : settings;
    }

    public void reset() {
        this.settings = Settings.NONE;
    }

    public void delayPgCall() {
        long millis = settings.pgDelayMs();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean shouldTimeoutAfterCapture() {
        return settings.pgTimeoutAfterCapture();
    }

    public boolean shouldDeclinePg() {
        return settings.pgDecline();
    }

    /** 켜져 있으면 PayPal 캡처 요청에 붙일 오류 코드, 꺼져 있으면 null. */
    public String paypalMockResponse() {
        return settings.paypalMockResponse();
    }

    public void failDbWriteIfRequested() {
        if (settings.dbFailureAfterCapture()) {
            throw new SimulatedDbFailureException("주입한 장애: PG 결제 뒤 DB 반영 실패");
        }
    }

    /** paypalMockResponse는 빼고 보내도 된다. 그러면 꺼진다. */
    public record Settings(long pgDelayMs, boolean pgTimeoutAfterCapture, boolean dbFailureAfterCapture,
                           boolean pgDecline, String paypalMockResponse) {

        public static final Settings NONE = new Settings(0, false, false, false, null);

        public Settings withPgDelay(Duration delay) {
            return new Settings(delay.toMillis(), pgTimeoutAfterCapture, dbFailureAfterCapture, pgDecline, paypalMockResponse);
        }

        public Settings withPgTimeoutAfterCapture() {
            return new Settings(pgDelayMs, true, dbFailureAfterCapture, pgDecline, paypalMockResponse);
        }

        public Settings withDbFailureAfterCapture() {
            return new Settings(pgDelayMs, pgTimeoutAfterCapture, true, pgDecline, paypalMockResponse);
        }

        public Settings withPgDecline() {
            return new Settings(pgDelayMs, pgTimeoutAfterCapture, dbFailureAfterCapture, true, paypalMockResponse);
        }

        public Settings withPaypalMockResponse(String code) {
            return new Settings(pgDelayMs, pgTimeoutAfterCapture, dbFailureAfterCapture, pgDecline, code);
        }
    }
}
