package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.AddressEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<AddressEntity, Long> {
    List<AddressEntity> findByUserId(java.util.UUID userId);
}
