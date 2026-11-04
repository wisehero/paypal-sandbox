package dev.wisehero.paypalsandbox.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * 결제 확정과 같은 트랜잭션에 쌓는 후속 작업 이벤트.
 * 메일, 물류 연동 같은 일은 커밋된 이 행을 읽어서 따로 처리한다. 이 프로젝트에는 발행기를 두지 않았다.
 */
@Entity
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;

    private Long orderId;

    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String type, Long orderId) {
        this.type = type;
        this.orderId = orderId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Long getOrderId() {
        return orderId;
    }
}
