package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.OrderLine;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import dev.wisehero.paypalsandbox.pg.RoutingPaymentGateway;
import dev.wisehero.paypalsandbox.product.Product;
import dev.wisehero.paypalsandbox.product.ProductRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String CURRENCY = "USD";

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final RoutingPaymentGateway gateway;
    private final InTransactionCheckout inTransactionCheckout;
    private final SplitTransactionCheckout splitTransactionCheckout;
    private final TransactionTemplate tx;

    public CreatedOrder create(Long productId, int quantity) {
        return create(productId, quantity, gateway.defaultProvider());
    }

    public CreatedOrder create(Long productId, int quantity, PgProvider provider) {
        return create(List.of(new Item(productId, quantity)), provider, null);
    }

    /**
     * 주문을 먼저 커밋하고, PG 주문 생성은 트랜잭션 밖에서 부른다.
     * 재고는 여기서 확인만 하고, 실제로 줄이는 건 캡처 단계에서 한다.
     *
     * @param requestedStrategy 체크아웃에서 고른 결제 처리 구조. null이면 캡처할 때 고른다.
     */
    public CreatedOrder create(List<Item> items, PgProvider provider, CaptureStrategy requestedStrategy) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (Item item : items) {
            if (item.quantity() < 1) {
                throw new IllegalArgumentException("수량은 1 이상: product=" + item.productId());
            }
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("주문 상품이 없다");
        }

        PurchaseOrder saved = tx.execute(status -> {
            List<OrderLine> lines = quantities.entrySet().stream().map(e -> {
                Product product = products.findById(e.getKey())
                        .orElseThrow(() -> new IllegalArgumentException("상품 없음: " + e.getKey()));
                if (product.getStock() < e.getValue()) {
                    throw new IllegalArgumentException("재고 부족: " + product.getName() + " (남은 재고 " + product.getStock() + ")");
                }
                return new OrderLine(product.getId(), product.getName(), product.getPrice(), e.getValue());
            }).toList();
            return orders.save(new PurchaseOrder(lines, CURRENCY, requestedStrategy));
        });

        PaymentGateway.PgOrder pgOrder;
        try {
            pgOrder = gateway.createOrder(provider, String.valueOf(saved.getId()), saved.getAmount(), saved.getCurrency());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> orders.findById(saved.getId()).orElseThrow()
                    .markFailed("PG 주문 생성 실패: " + e.getMessage()));
            throw e;
        }

        tx.executeWithoutResult(status -> orders.findById(saved.getId()).orElseThrow()
                .attachPgOrder(provider, pgOrder.pgOrderId()));
        return new CreatedOrder(saved.getId(), pgOrder.pgOrderId(), pgOrder.approveUrl());
    }

    public PurchaseOrder capture(Long orderId, CaptureStrategy strategy) {
        return switch (strategy) {
            case IN_TX -> inTransactionCheckout.capture(orderId);
            case SPLIT -> splitTransactionCheckout.capture(orderId);
        };
    }

    public PurchaseOrder get(Long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public List<PurchaseOrder> list() {
        return orders.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    public PaymentGateway.PgOrderSnapshot pgSnapshot(Long orderId) {
        PurchaseOrder order = get(orderId);
        if (order.getPgOrderId() == null) {
            throw new IllegalStateException("PG 주문이 없는 주문: " + orderId);
        }
        return gateway.getOrder(order.getPgOrderId());
    }

    public PurchaseOrder getByPgOrderId(String pgOrderId) {
        return orders.findByPgOrderId(pgOrderId).orElseThrow(() -> new OrderNotFoundException(pgOrderId));
    }

    public record Item(Long productId, int quantity) {
    }

    public record CreatedOrder(Long orderId, String pgOrderId, String approveUrl) {
    }
}
