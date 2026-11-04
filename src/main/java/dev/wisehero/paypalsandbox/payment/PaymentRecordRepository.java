package dev.wisehero.paypalsandbox.payment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {

    boolean existsByCaptureId(String captureId);

    List<PaymentRecord> findByOrderId(Long orderId);
}
