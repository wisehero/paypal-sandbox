package dev.wisehero.paypalsandbox.product;

import java.math.BigDecimal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ProductSeeder implements ApplicationRunner {

    private final ProductRepository products;

    ProductSeeder(ProductRepository products) {
        this.products = products;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (products.count() == 0) {
            products.save(new Product("Sample Item A", new BigDecimal("19.90"), 100));
            products.save(new Product("Sample Item B", new BigDecimal("12.50"), 100));
        }
    }
}
