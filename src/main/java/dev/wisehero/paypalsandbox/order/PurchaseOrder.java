package dev.wisehero.paypalsandbox.order;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    private static final Set<OrderStatus> PAYABLE =
            EnumSet.of(OrderStatus.CREATED, OrderStatus.PAYMENT_IN_PROGRESS, OrderStatus.PAYMENT_UNKNOWN);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;

    private int quantity;

    private BigDecimal amount;

    private String currency;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    private CaptureStrategy captureStrategy;

    private String pgOrderId;

    private String captureId;

    private String failureReason;

    private Instant createdAt;

    private Instant updatedAt;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(Long productId, int quantity, BigDecimal amount, String currency) {
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void attachPgOrder(String pgOrderId) {
        this.pgOrderId = pgOrderId;
    }

    /** 트랜잭션 안 구조: 상태는 CREATED 그대로 두고, 같은 트랜잭션 안에서 바로 PAID로 바꾼다. */
    public void beginInTransactionCapture() {
        requireCapturable();
        this.captureStrategy = CaptureStrategy.IN_TX;
    }

    /** 트랜잭션 분리 구조: PG를 부르기 전에 "결제 시작" 흔적을 먼저 커밋한다. */
    public void startPayment() {
        requireCapturable();
        this.captureStrategy = CaptureStrategy.SPLIT;
        this.status = OrderStatus.PAYMENT_IN_PROGRESS;
    }

    private void requireCapturable() {
        requireStatus(OrderStatus.CREATED);
        if (pgOrderId == null) {
            throw new IllegalStateException("PG 주문이 없는 주문: " + id);
        }
    }

    public void markPaid(String captureId) {
        if (!PAYABLE.contains(status)) {
            throw new IllegalStateException("결제 완료로 바꿀 수 없는 상태: order=" + id + ", status=" + status);
        }
        this.status = OrderStatus.PAID;
        this.captureId = captureId;
    }

    public void markUnknown() {
        requireStatus(OrderStatus.PAYMENT_IN_PROGRESS);
        this.status = OrderStatus.PAYMENT_UNKNOWN;
    }

    public void markFailed(String reason) {
        if (!PAYABLE.contains(status)) {
            throw new IllegalStateException("결제 실패로 바꿀 수 없는 상태: order=" + id + ", status=" + status);
        }
        this.status = OrderStatus.PAYMENT_FAILED;
        this.failureReason = reason;
    }

    public void markRefunded(String captureId) {
        requireStatus(OrderStatus.PAYMENT_FAILED);
        this.status = OrderStatus.REFUNDED;
        this.captureId = captureId;
    }

    /** PG 결과를 기다리는 상태인지. 이 상태의 주문은 결제 시작 때 재고를 예약해 두었다. */
    public boolean isAwaitingPgResult() {
        return status == OrderStatus.PAYMENT_IN_PROGRESS || status == OrderStatus.PAYMENT_UNKNOWN;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("주문 상태가 " + expected + "가 아님: order=" + id + ", status=" + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public CaptureStrategy getCaptureStrategy() {
        return captureStrategy;
    }

    public String getPgOrderId() {
        return pgOrderId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
