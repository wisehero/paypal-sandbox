package dev.wisehero.paypalsandbox.fault;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 실험용 장애 주입 스위치. 한 번 켜면 끌 때까지 유지된다.
 *
 * <ul>
 *   <li>pgDelay: PG 호출마다 이만큼 늦게 응답한다.</li>
 *   <li>pgTimeoutAfterCapture: PG는 결제를 끝냈는데 우리 쪽은 응답을 못 받은 것처럼 만든다.</li>
 *   <li>dbFailureAfterCapture: PG 결제가 끝난 뒤 DB 반영 단계에서 예외를 던져 롤백시킨다.</li>
 *   <li>pgDecline: PG가 결제를 거절한다.</li>
 * </ul>
 */
@Component
public class FaultInjector {

    private volatile Settings settings = Settings.NONE;

    public Settings current() {
        return settings;
    }

    public void apply(Settings settings) {
        this.settings = settings;
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

    public void failDbWriteIfRequested() {
        if (settings.dbFailureAfterCapture()) {
            throw new SimulatedDbFailureException("주입한 장애: PG 결제 뒤 DB 반영 실패");
        }
    }

    public record Settings(long pgDelayMs, boolean pgTimeoutAfterCapture, boolean dbFailureAfterCapture, boolean pgDecline) {

        public static final Settings NONE = new Settings(0, false, false, false);

        public Settings withPgDelay(Duration delay) {
            return new Settings(delay.toMillis(), pgTimeoutAfterCapture, dbFailureAfterCapture, pgDecline);
        }

        public Settings withPgTimeoutAfterCapture() {
            return new Settings(pgDelayMs, true, dbFailureAfterCapture, pgDecline);
        }

        public Settings withDbFailureAfterCapture() {
            return new Settings(pgDelayMs, pgTimeoutAfterCapture, true, pgDecline);
        }

        public Settings withPgDecline() {
            return new Settings(pgDelayMs, pgTimeoutAfterCapture, dbFailureAfterCapture, true);
        }
    }
}
