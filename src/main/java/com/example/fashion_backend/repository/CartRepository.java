package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.CartEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<CartEntity, UUID> {
    Optional<CartEntity> findByGuestId(String guestId);
    Optional<CartEntity> findByUserId(UUID userId);
}
