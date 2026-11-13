package com.example.fashion_backend.controller.admin;

import com.example.fashion_backend.dto.review.ReviewResponse;
import com.example.fashion_backend.entity.ProductReviewVisibility;
import com.example.fashion_backend.service.ProductReviewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReviewController {
    private final ProductReviewService reviewService;

    public AdminReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<List<ReviewResponse>> list() {
        return ResponseEntity.ok(reviewService.listForAdmin());
    }

    @PutMapping("/{id}/visibility")
    public ResponseEntity<ReviewResponse> setVisibility(
            @PathVariable Long id,
            @RequestBody String visibility,
            Authentication authentication) {
        ProductReviewVisibility value = ProductReviewVisibility.valueOf(visibility.replace("\"", "").trim().toUpperCase());
        return ResponseEntity.ok(reviewService.setVisibility(id, value, "Admin visibility change", authentication));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        reviewService.deleteByAdmin(id);
        return ResponseEntity.noContent().build();
    }
}