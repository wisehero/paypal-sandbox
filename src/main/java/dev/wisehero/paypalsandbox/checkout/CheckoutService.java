package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 쇼핑 화면의 체크아웃 흐름.
 * 장바구니 → 주문 생성 → PG 승인 화면 → 돌아와서 캡처 → 결제 완료 화면.
 */
@Service
public class CheckoutService {

    private final OrderService orderService;

    CheckoutService(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderService.CreatedOrder start(List<OrderService.Item> items, PgProvider provider, CaptureStrategy strategy) {
        return orderService.create(items, provider, strategy);
    }

    /**
     * PG 승인 후 돌아왔을 때 부른다. 체크아웃에서 고른 구조로 캡처한다.
     * 캡처가 실패해도 예외를 던지지 않고 주문의 현재 상태와 오류를 돌려준다. 화면은 그 상태를 보여 준다.
     */
    public CompleteResult complete(String pgOrderId) {
        PurchaseOrder order = orderService.getByPgOrderId(pgOrderId);
        if (order.getRequestedStrategy() == null) {
            throw new IllegalStateException("실험 콘솔에서 만든 주문은 콘솔에서 캡처한다: order=" + order.getId());
        }
        // 같은 화면을 새로고침해서 두 번 들어와도 다시 캡처하지 않는다.
        if (order.getStatus() != OrderStatus.CREATED || order.getCaptureStrategy() != null) {
            return new CompleteResult(order.getId(), order.getStatus(), null);
        }

        String error = null;
        try {
            orderService.capture(order.getId(), order.getRequestedStrategy());
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        return new CompleteResult(order.getId(), orderService.get(order.getId()).getStatus(), error);
    }

    public record CompleteResult(Long orderId, OrderStatus status, String error) {
    }
}
