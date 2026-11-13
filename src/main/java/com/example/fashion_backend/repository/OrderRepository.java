package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    // No collection fetch join: PostgreSQL cannot lock the nullable side of an outer join.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from OrderEntity o where o.id = :id")
    java.util.Optional<OrderEntity> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

    @EntityGraph(attributePaths = {"items"})
    java.util.Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);
	@EntityGraph(attributePaths = {"items"})
	List<OrderEntity> findByUserId(UUID userId);

	@Override
	@EntityGraph(attributePaths = {"items"})
	java.util.Optional<OrderEntity> findById(UUID id);
}
