package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.review.ReviewRequest;
import com.example.fashion_backend.dto.review.ReviewResponse;
import com.example.fashion_backend.service.ProductReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
@Validated
public class ProductReviewController {
    private final ProductReviewService reviewService;

    public ProductReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<List<ReviewResponse>> list(@RequestParam("productId") @NotBlank String productId) {
        return ResponseEntity.ok(reviewService.listByProduct(productId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReviewResponse> create(
            @Valid @RequestPart("review") ReviewRequest request,
            @RequestPart(value = "images", required = false) MultipartFile[] images,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.create(request, authentication, images));
    }
}
