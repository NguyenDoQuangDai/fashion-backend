package com.example.fashion_backend.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_product_views")
public class UserProductViewEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "product_id", nullable = false)
    private String productId;
    @Column(name = "last_viewed_at", nullable = false)
    private OffsetDateTime lastViewedAt;

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public String getProductId() { return productId; }
    public void setProductId(String value) { productId = value; }
    public OffsetDateTime getLastViewedAt() { return lastViewedAt; }
    public void setLastViewedAt(OffsetDateTime value) { lastViewedAt = value; }
}
