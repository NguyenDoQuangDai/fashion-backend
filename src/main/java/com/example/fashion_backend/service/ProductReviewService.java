package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.review.ReviewRequest;
import com.example.fashion_backend.dto.review.ReviewResponse;
import com.example.fashion_backend.entity.ProductReviewEntity;
import com.example.fashion_backend.repository.ProductReviewRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductReviewService {
    private final ProductReviewRepository reviewRepository;

    public ProductReviewService(ProductReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listByProduct(String productId) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReviewResponse create(ReviewRequest request) {
        ProductReviewEntity entity = new ProductReviewEntity();
        entity.setProductId(request.getProductId());
        entity.setGuestName(request.getGuestName());
        entity.setGuestEmail(request.getGuestEmail());
        entity.setRating(request.getRating());
        entity.setComment(request.getComment());
        entity.setCreatedAt(OffsetDateTime.now());
        return mapToResponse(reviewRepository.save(entity));
    }

    private ReviewResponse mapToResponse(ProductReviewEntity entity) {
        ReviewResponse response = new ReviewResponse();
        response.setId(entity.getId());
        response.setProductId(entity.getProductId());
        response.setGuestName(entity.getGuestName());
        response.setGuestEmail(entity.getGuestEmail());
        response.setRating(entity.getRating());
        response.setComment(entity.getComment());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
