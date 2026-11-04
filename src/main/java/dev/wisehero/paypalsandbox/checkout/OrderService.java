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
     * 주문 생성. 우리 주문을 먼저 커밋하고, PG 주문은 트랜잭션 밖에서 만든다.
     * 재고는 확인만 하고 줄이지 않는다. 재고는 캡처할 때 잡는다.
     *
     * @param requestedStrategy 체크아웃에서 고른 결제 처리 구조. null이면 캡처할 때 고른다.
     */
    public CreatedOrder create(List<Item> items, PgProvider provider, CaptureStrategy requestedStrategy) {
        PurchaseOrder order = saveOrder(mergeQuantities(items), requestedStrategy);   // 트랜잭션 1
        PaymentGateway.PgOrder pgOrder = createPgOrder(order, provider);              // 트랜잭션 밖
        attachPgOrder(order.getId(), provider, pgOrder);                               // 트랜잭션 2
        return new CreatedOrder(order.getId(), pgOrder.pgOrderId(), pgOrder.approveUrl());
    }

    private PurchaseOrder saveOrder(Map<Long, Integer> quantities, CaptureStrategy requestedStrategy) {
        return tx.execute(status ->
                orders.save(new PurchaseOrder(snapshotLines(quantities), CURRENCY, requestedStrategy)));
    }

    /** PG에 우리 주문 ID를 실어 주문을 만든다. 실패하면 우리 주문을 실패로 남긴다. */
    private PaymentGateway.PgOrder createPgOrder(PurchaseOrder order, PgProvider provider) {
        try {
            return gateway.createOrder(provider, String.valueOf(order.getId()), order.getAmount(), order.getCurrency());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status ->
                    orders.getOrThrow(order.getId()).markFailed("PG 주문 생성 실패: " + e.getMessage()));
            throw e;
        }
    }

    private void attachPgOrder(Long orderId, PgProvider provider, PaymentGateway.PgOrder pgOrder) {
        tx.executeWithoutResult(status -> orders.getOrThrow(orderId).attachPgOrder(provider, pgOrder.pgOrderId()));
    }

    /** 같은 상품이 여러 줄로 들어오면 수량을 합친다. 들어온 순서는 유지한다. */
    private static Map<Long, Integer> mergeQuantities(List<Item> items) {
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
        return quantities;
    }

    /** 지금 상품명과 가격을 주문 줄에 복사한다. 재고는 확인만 하고 줄이지 않는다. */
    private List<OrderLine> snapshotLines(Map<Long, Integer> quantities) {
        return quantities.entrySet().stream().map(e -> {
            Product product = products.getOrThrow(e.getKey());
            if (product.getStock() < e.getValue()) {
                throw new IllegalArgumentException("재고 부족: " + product.getName() + " (남은 재고 " + product.getStock() + ")");
            }
            return new OrderLine(product.getId(), product.getName(), product.getPrice(), e.getValue());
        }).toList();
    }

    public PurchaseOrder capture(Long orderId, CaptureStrategy strategy) {
        return switch (strategy) {
            case IN_TX -> inTransactionCheckout.capture(orderId);
            case SPLIT -> splitTransactionCheckout.capture(orderId);
        };
    }

    public PurchaseOrder get(Long orderId) {
        return orders.getOrThrow(orderId);
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
        return orders.getByPgOrderIdOrThrow(pgOrderId);
    }

    public record Item(Long productId, int quantity) {
    }

    public record CreatedOrder(Long orderId, String pgOrderId, String approveUrl) {
    }
}
