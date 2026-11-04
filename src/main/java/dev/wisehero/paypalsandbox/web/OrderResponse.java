package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.OrderLine;
import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 화면에 내보내는 주문. 엔티티를 그대로 직렬화하지 않는다. */
record OrderResponse(
        Long id,
        List<Line> lines,
        BigDecimal amount,
        String currency,
        OrderStatus status,
        boolean awaitingPgResult,
        CaptureStrategy requestedStrategy,
        CaptureStrategy captureStrategy,
        PgProvider pgProvider,
        String pgOrderId,
        String captureId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static OrderResponse from(PurchaseOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getLines().stream().map(Line::from).toList(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.isAwaitingPgResult(),
                order.getRequestedStrategy(),
                order.getCaptureStrategy(),
                order.getPgProvider(),
                order.getPgOrderId(),
                order.getCaptureId(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    record Line(Long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal amount) {

        static Line from(OrderLine line) {
            return new Line(line.getProductId(), line.getProductName(), line.getUnitPrice(), line.getQuantity(),
                    line.getAmount());
        }
    }
}
