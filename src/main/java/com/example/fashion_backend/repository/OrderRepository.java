package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.OrderEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
	@EntityGraph(attributePaths = {"items"})
	List<OrderEntity> findByUserId(UUID userId);

	@Override
	@EntityGraph(attributePaths = {"items"})
	java.util.Optional<OrderEntity> findById(UUID id);
}
