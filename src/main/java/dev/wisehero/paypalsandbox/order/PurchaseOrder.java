package dev.wisehero.paypalsandbox.order;

import dev.wisehero.paypalsandbox.pg.PgProvider;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    private static final Set<OrderStatus> PAYABLE =
            EnumSet.of(OrderStatus.CREATED, OrderStatus.PAYMENT_IN_PROGRESS, OrderStatus.PAYMENT_UNKNOWN);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 행 잠금 쿼리에 조인이 섞이지 않도록 별도 쿼리로 읽는다.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "purchase_order_line", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLine> lines = new ArrayList<>();

    private BigDecimal amount;

    private String currency;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    /** 체크아웃에서 고른 결제 처리 구조. 실험 콘솔에서 만든 주문은 비어 있고 캡처할 때 고른다. */
    @Enumerated(EnumType.STRING)
    private CaptureStrategy requestedStrategy;

    /** 실제로 캡처를 시도한 구조. 트랜잭션 안 구조가 롤백되면 이 값도 함께 사라진다. */
    @Enumerated(EnumType.STRING)
    private CaptureStrategy captureStrategy;

    private String pgOrderId;

    @Enumerated(EnumType.STRING)
    private PgProvider pgProvider;

    private String captureId;

    private String failureReason;

    private Instant createdAt;

    private Instant updatedAt;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(List<OrderLine> lines, String currency, CaptureStrategy requestedStrategy) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문 상품이 없다");
        }
        this.lines = new ArrayList<>(lines);
        this.amount = lines.stream().map(OrderLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.currency = currency;
        this.requestedStrategy = requestedStrategy;
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

    public void attachPgOrder(PgProvider provider, String pgOrderId) {
        this.pgProvider = provider;
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
        // PG 오류 응답을 통째로 담으면 컬럼(255자)을 넘는다. 전체 응답은 PG 통신 로그에 남아 있다.
        this.failureReason = reason != null && reason.length() > 255 ? reason.substring(0, 252) + "..." : reason;
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

    public List<OrderLine> getLines() {
        return List.copyOf(lines);
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

    public CaptureStrategy getRequestedStrategy() {
        return requestedStrategy;
    }

    public CaptureStrategy getCaptureStrategy() {
        return captureStrategy;
    }

    public PgProvider getPgProvider() {
        return pgProvider;
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
