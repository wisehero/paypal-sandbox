package dev.wisehero.paypalsandbox.pg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

/**
 * PG에 보낸 요청과 받은 응답을 그대로 남긴다.
 *
 * <p>응답 본문을 로그와 RestClient가 함께 읽어야 하므로 BufferingClientHttpRequestFactory와 같이 쓴다.
 * 인증 헤더와 액세스 토큰만 가린다. 응답을 못 받은 경우(타임아웃, 연결 실패)도 남긴다.
 */
public class PgHttpLogging implements ClientHttpRequestInterceptor {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("(\"access_token\"\\s*:\\s*\")[^\"]*(\")");

    private final String pgName;
    private final Logger log;

    public PgHttpLogging(String pgName) {
        this.pgName = pgName;
        this.log = LoggerFactory.getLogger("pg.http." + pgName.toLowerCase());
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        log.info("{} 요청 {} {}\n  headers: {}\n  body: {}", pgName, request.getMethod(), request.getURI(),
                headers(request.getHeaders()), text(body));

        long started = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            log.warn("{} 응답 없음 {} {} ({}ms): {}", pgName, request.getMethod(), request.getURI(),
                    elapsedMs(started), e.toString());
            throw e;
        }

        String responseBody = text(StreamUtils.copyToByteArray(response.getBody()));
        log.info("{} 응답 {} {} -> {} ({}ms)\n  headers: {}\n  body: {}", pgName, request.getMethod(),
                request.getURI(), response.getStatusCode().value(), elapsedMs(started),
                headers(response.getHeaders()), ACCESS_TOKEN.matcher(responseBody).replaceAll("$1***$2"));
        return response;
    }

    private static String headers(HttpHeaders headers) {
        return headers.headerSet().stream()
                .map(e -> e.getKey() + "=" + mask(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String mask(String name, List<String> values) {
        if (!HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
            return values.size() == 1 ? values.getFirst() : values.toString();
        }
        // "Basic xxx", "Bearer xxx"에서 방식만 남긴다.
        return values.stream()
                .map(v -> v.contains(" ") ? v.substring(0, v.indexOf(' ')) + " ***" : "***")
                .collect(Collectors.joining(","));
    }

    private static String text(byte[] body) {
        return body.length == 0 ? "(없음)" : new String(body, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
