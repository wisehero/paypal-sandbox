package dev.wisehero.paypalsandbox.order;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문 시점의 상품명과 단가를 그대로 남긴다. 상품 가격이 바뀌어도 주문 금액은 바뀌지 않는다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OrderLine {

    private Long productId;

    private String productName;

    private BigDecimal unitPrice;

    private int quantity;

    public BigDecimal getAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
