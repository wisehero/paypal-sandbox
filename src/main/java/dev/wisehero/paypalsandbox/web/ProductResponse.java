package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.product.Product;
import java.math.BigDecimal;

record ProductResponse(Long id, String name, BigDecimal price, int stock) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}
