package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.ProductEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    @Override
    @EntityGraph(attributePaths = {"colors", "colors.images", "colors.sizes"})
    List<ProductEntity> findAll();

    @Override
    @EntityGraph(attributePaths = {"colors", "colors.images", "colors.sizes"})
    java.util.Optional<ProductEntity> findById(String id);
}
