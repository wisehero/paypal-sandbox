package dev.wisehero.paypalsandbox.product;

import dev.wisehero.paypalsandbox.order.OrderLine;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상품의 재고를 잠그고 줄이거나 돌려준다.
 * 여러 상품을 잠글 때 항상 상품 ID 순서로 잠가서 교착 상태를 피한다.
 */
@Component
@RequiredArgsConstructor
public class StockLedger {

    private final ProductRepository products;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(PurchaseOrder order) {
        for (OrderLine line : sorted(order)) {
            products.findByIdForUpdate(line.getProductId()).orElseThrow().decreaseStock(line.getQuantity());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(PurchaseOrder order) {
        for (OrderLine line : sorted(order)) {
            products.findByIdForUpdate(line.getProductId()).orElseThrow().increaseStock(line.getQuantity());
        }
    }

    private static List<OrderLine> sorted(PurchaseOrder order) {
        return order.getLines().stream().sorted(Comparator.comparing(OrderLine::getProductId)).toList();
    }
}
