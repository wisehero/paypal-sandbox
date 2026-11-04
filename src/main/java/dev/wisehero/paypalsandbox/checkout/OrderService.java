package dev.wisehero.paypalsandbox.checkout;

import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.pg.PaymentGateway;
import dev.wisehero.paypalsandbox.product.Product;
import dev.wisehero.paypalsandbox.product.ProductRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private static final String CURRENCY = "USD";

    private final PurchaseOrderRepository orders;
    private final ProductRepository products;
    private final PaymentGateway gateway;
    private final InTransactionCheckout inTransactionCheckout;
    private final SplitTransactionCheckout splitTransactionCheckout;
    private final TransactionTemplate tx;

    OrderService(PurchaseOrderRepository orders, ProductRepository products, PaymentGateway gateway,
            InTransactionCheckout inTransactionCheckout, SplitTransactionCheckout splitTransactionCheckout,
            TransactionTemplate tx) {
        this.orders = orders;
        this.products = products;
        this.gateway = gateway;
        this.inTransactionCheckout = inTransactionCheckout;
        this.splitTransactionCheckout = splitTransactionCheckout;
        this.tx = tx;
    }

    /** 주문을 먼저 커밋하고, PG 주문 생성은 트랜잭션 밖에서 부른다. */
    public CreatedOrder create(Long productId, int quantity) {
        PurchaseOrder saved = tx.execute(status -> {
            Product product = products.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));
            BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            return orders.save(new PurchaseOrder(productId, quantity, amount, CURRENCY));
        });

        PaymentGateway.PgOrder pgOrder;
        try {
            pgOrder = gateway.createOrder(String.valueOf(saved.getId()), saved.getAmount(), saved.getCurrency());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> orders.findById(saved.getId()).orElseThrow()
                    .markFailed("PG 주문 생성 실패: " + e.getMessage()));
            throw e;
        }

        tx.executeWithoutResult(status -> orders.findById(saved.getId()).orElseThrow()
                .attachPgOrder(pgOrder.pgOrderId()));
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

    public PurchaseOrder getByPgOrderId(String pgOrderId) {
        return orders.findByPgOrderId(pgOrderId).orElseThrow(() -> new OrderNotFoundException(pgOrderId));
    }

    public record CreatedOrder(Long orderId, String pgOrderId, String approveUrl) {
    }
}
