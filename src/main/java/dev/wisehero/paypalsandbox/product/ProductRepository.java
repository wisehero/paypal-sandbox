package dev.wisehero.paypalsandbox.product;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(Long id);

    default Product getOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("상품 없음: " + id));
    }

    /** 행 락(SELECT ... FOR UPDATE)을 걸고 읽는다. 트랜잭션 안에서만 부른다. */
    default Product lockOrThrow(Long id) {
        return findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("상품 없음: " + id));
    }
}
