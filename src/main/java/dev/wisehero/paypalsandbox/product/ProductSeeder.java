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
            products.save(new Product("Notebook", new BigDecimal("12.50"), 100));
            products.save(new Product("Gel Pen Set", new BigDecimal("8.90"), 100));
            products.save(new Product("Desk Lamp", new BigDecimal("34.00"), 30));
            products.save(new Product("Ceramic Mug", new BigDecimal("15.00"), 50));
            products.save(new Product("Canvas Tote Bag", new BigDecimal("22.00"), 40));
            products.save(new Product("Sticky Notes", new BigDecimal("4.50"), 200));
        }
    }
}
