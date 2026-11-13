package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.VnpayPaymentEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VnpayPaymentRepository extends JpaRepository<VnpayPaymentEntity, UUID> {
    @org.springframework.data.jpa.repository.Query("select p.order.id from VnpayPaymentEntity p where p.txnRef = :txnRef")
    Optional<UUID> findOrderIdByTxnRef(@org.springframework.data.repository.query.Param("txnRef") String txnRef);
    Optional<VnpayPaymentEntity> findByTxnRef(String txnRef);
    Optional<VnpayPaymentEntity> findByOrderId(UUID orderId);
}
