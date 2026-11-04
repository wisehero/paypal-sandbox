package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.checkout.OrderService;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    OrderService.CreatedOrder create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request.productId(), request.quantity());
    }

    @PostMapping("/{orderId}/capture")
    PurchaseOrder capture(@PathVariable Long orderId, @RequestParam String strategy) {
        return orderService.capture(orderId, CaptureStrategy.from(strategy));
    }

    @GetMapping("/{orderId}")
    PurchaseOrder get(@PathVariable Long orderId) {
        return orderService.get(orderId);
    }

    /** PayPal 승인 후 돌아오는 주소. PayPal이 token 파라미터로 PG 주문 ID를 넘긴다. */
    @GetMapping(value = "/return", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    String approved(@RequestParam("token") String pgOrderId) {
        PurchaseOrder order = orderService.getByPgOrderId(pgOrderId);
        return """
                구매자 승인 완료. 주문 %d (PayPal %s)
                이제 둘 중 한 구조로 캡처한다.
                  curl -X POST 'http://localhost:8080/orders/%d/capture?strategy=in-tx'
                  curl -X POST 'http://localhost:8080/orders/%d/capture?strategy=split'
                """.formatted(order.getId(), pgOrderId, order.getId(), order.getId());
    }

    @GetMapping(value = "/cancel", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    String cancelled(@RequestParam(value = "token", required = false) String pgOrderId) {
        return "구매자가 승인을 취소했다. PayPal 주문: " + pgOrderId;
    }

    record CreateOrderRequest(@NotNull Long productId, @Min(1) int quantity) {
    }
}
