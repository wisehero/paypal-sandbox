package dev.wisehero.paypalsandbox.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(Long id);

    Optional<PurchaseOrder> findByPgOrderId(String pgOrderId);

    List<PurchaseOrder> findByStatusInAndUpdatedAtBefore(Collection<OrderStatus> statuses, Instant before);

    List<PurchaseOrder> findByStatusNotInAndPgOrderIdIsNotNull(Collection<OrderStatus> statuses);
}
