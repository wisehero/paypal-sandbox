package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.pg.EximbayPaymentGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 엑심베이 결제창과 주고받는 주소.
 * return_url은 구매자 브라우저가, status_url은 엑심베이 서버가 부른다. 두 곳 모두 같은 파라미터가 온다.
 */
@RestController
@RequestMapping("/eximbay")
@ConditionalOnExpression("!'${pg.eximbay.api-key:}'.isEmpty()")
@Slf4j
@RequiredArgsConstructor
class EximbayController {

    private final EximbayPaymentGateway gateway;

    /** 결제창 화면(/eximbay-pay.html)이 SDK를 부를 때 쓰는 값. */
    @GetMapping("/params/{pgOrderId}")
    EximbayPaymentGateway.SdkParams params(@PathVariable String pgOrderId) {
        return gateway.sdkParams(pgOrderId);
    }

    /** 구매자 브라우저가 돌아오는 주소. 검증한 뒤 공통 결제 처리 화면으로 넘긴다. */
    @RequestMapping(value = "/return", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<Void> returned(HttpServletRequest request) {
        EximbayPaymentGateway.AuthResult result = handle(request);
        log.info("엑심베이 return_url: order={}, success={}, message={}", result.pgOrderId(), result.success(), result.message());

        String location = result.success()
                ? "/checkout-return.html?token=" + encode(result.pgOrderId())
                : "/checkout.html?cancelled=1&reason=" + encode(result.message());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }

    /**
     * 엑심베이 서버가 직접 부르는 주소. 처리 결과를 rescode, resmsg로 답해야 한다.
     * localhost에서는 호출되지 않는다. 받아 보려면 터널 주소를 EXIMBAY_STATUS_URL로 넘긴다.
     */
    @RequestMapping(value = "/status", method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_PLAIN_VALUE)
    String status(HttpServletRequest request) {
        EximbayPaymentGateway.AuthResult result = handle(request);
        log.info("엑심베이 status_url: order={}, success={}, message={}", result.pgOrderId(), result.success(), result.message());
        return result.success() ? "rescode=0000&resmsg=Success" : "rescode=9999&resmsg=" + result.message();
    }

    private EximbayPaymentGateway.AuthResult handle(HttpServletRequest request) {
        Map<String, String> params = params(request);
        try {
            return gateway.handleAuthResult(params);
        } catch (RuntimeException e) {
            return new EximbayPaymentGateway.AuthResult(params.get("order_id"), false, "결제 검증 중 오류: " + e.getMessage());
        }
    }

    /** 받은 순서 그대로, 디코딩한 값으로 모은다. 결제 검증 API에 이 순서와 값 그대로 보낸다. */
    private static Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v.length > 0 ? v[0] : ""));
        return params;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
