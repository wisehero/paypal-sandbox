package dev.wisehero.paypalsandbox.order;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** 주문 시점의 상품명과 단가를 그대로 남긴다. 상품 가격이 바뀌어도 주문 금액은 바뀌지 않는다. */
@Embeddable
public class OrderLine {

    private Long productId;

    private String productName;

    private BigDecimal unitPrice;

    private int quantity;

    protected OrderLine() {
    }

    public OrderLine(Long productId, String productName, BigDecimal unitPrice, int quantity) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount();
    }
}
