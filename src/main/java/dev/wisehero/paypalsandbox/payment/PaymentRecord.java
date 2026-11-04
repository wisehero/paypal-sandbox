package dev.wisehero.paypalsandbox.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;

    private String pgOrderId;

    @Column(unique = true)
    private String captureId;

    private BigDecimal amount;

    private String currency;

    private Instant createdAt;

    protected PaymentRecord() {
    }

    public PaymentRecord(Long orderId, String pgOrderId, String captureId, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.pgOrderId = pgOrderId;
        this.captureId = captureId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getCaptureId() {
        return captureId;
    }
}
