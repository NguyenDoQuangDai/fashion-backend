package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.ProductReviewEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewRepository extends JpaRepository<ProductReviewEntity, Long> {
    List<ProductReviewEntity> findByProductIdOrderByCreatedAtDesc(String productId);
}
