package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.PaymentHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistoryEntity, UUID> {
    List<PaymentHistoryEntity> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
    boolean existsByPaymentIdAndSourceAndEventKey(UUID paymentId, String source, String eventKey);
}
