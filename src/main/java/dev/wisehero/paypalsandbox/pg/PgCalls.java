package dev.wisehero.paypalsandbox.pg;

import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PG HTTP 호출의 실패를 두 종류로 나눈다.
 *
 * <ul>
 *   <li>응답을 못 받음(연결 실패, 타임아웃), 5xx: PG 안에서 처리가 됐을 수도 있다. {@link PgTimeoutException}</li>
 *   <li>4xx: PG가 요청을 받아서 거절했다. {@link PgDeclinedException}</li>
 * </ul>
 */
final class PgCalls {

    private PgCalls() {
    }

    static <T> T call(String pgName, Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new PgTimeoutException(pgName + " 응답 없음: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new PgTimeoutException(pgName + " 5xx: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            }
            throw new PgDeclinedException(pgName + " " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }
}
