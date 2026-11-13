package com.example.fashion_backend.repository;

import com.example.fashion_backend.entity.UserProductViewEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserProductViewRepository extends JpaRepository<UserProductViewEntity, Long> {
    List<UserProductViewEntity> findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(
            UUID userId, OffsetDateTime cutoff);

    // Atomic upsert: concurrent requests/retries cannot create duplicate rows or inflate counts.
    @Modifying
    @Query(value = """
        INSERT INTO user_product_views(user_id, product_id, last_viewed_at)
        VALUES (:userId, :productId, :now)
        ON CONFLICT (user_id, product_id) DO UPDATE SET last_viewed_at = EXCLUDED.last_viewed_at
        WHERE user_product_views.last_viewed_at <= :cutoff
        """, nativeQuery = true)
    int recordView(@Param("userId") UUID userId, @Param("productId") String productId,
                   @Param("now") OffsetDateTime now, @Param("cutoff") OffsetDateTime cutoff);
}
