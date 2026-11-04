package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.checkout.CheckoutService;
import dev.wisehero.paypalsandbox.checkout.OrderService;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
class CheckoutController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    CheckoutController(CheckoutService checkoutService, OrderService orderService) {
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    @PostMapping
    OrderService.CreatedOrder start(@Valid @RequestBody StartRequest request) {
        List<OrderService.Item> items = request.items().stream()
                .map(i -> new OrderService.Item(i.productId(), i.quantity()))
                .toList();
        return checkoutService.start(items, PgProvider.from(request.pg()), CaptureStrategy.from(request.strategy()));
    }

    /** PG 승인 화면과 돌아온 화면이 PG 주문 ID로 우리 주문을 찾을 때 쓴다. */
    @GetMapping("/pg-orders/{pgOrderId}")
    PurchaseOrder byPgOrder(@PathVariable String pgOrderId) {
        return orderService.getByPgOrderId(pgOrderId);
    }

    @PostMapping("/complete")
    CheckoutService.CompleteResult complete(@Valid @RequestBody CompleteRequest request) {
        return checkoutService.complete(request.token());
    }

    record StartRequest(@NotEmpty List<@Valid Item> items, @NotBlank String pg, @NotBlank String strategy) {
    }

    record Item(@NotNull Long productId, @Min(1) int quantity) {
    }

    record CompleteRequest(@NotBlank String token) {
    }
}
