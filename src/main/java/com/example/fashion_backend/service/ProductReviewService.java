package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.review.ReviewRequest;
import com.example.fashion_backend.dto.review.ReviewImageResponse;
import com.example.fashion_backend.dto.review.ReviewResponse;
import com.example.fashion_backend.entity.ProductReviewEntity;
import com.example.fashion_backend.entity.ProductReviewImageEntity;
import com.example.fashion_backend.entity.ProductReviewVisibility;
import com.example.fashion_backend.entity.ReviewMediaType;
import com.example.fashion_backend.entity.OrderEntity;
import com.example.fashion_backend.repository.OrderRepository;
import com.example.fashion_backend.repository.ProductReviewRepository;
import com.example.fashion_backend.security.UserPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductReviewService {
    private final ProductReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ReviewMediaStorage mediaStorage;

    public ProductReviewService(ProductReviewRepository reviewRepository,
                                OrderRepository orderRepository,
                                ReviewMediaStorage mediaStorage) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.mediaStorage = mediaStorage;
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listByProduct(String productId) {
        return reviewRepository.findByProductIdAndVisibilityOrderByCreatedAtDesc(productId, ProductReviewVisibility.VISIBLE).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> listForAdmin() {
        return reviewRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReviewResponse setVisibility(Long reviewId, ProductReviewVisibility visibility, String reason, Authentication authentication) {
        ProductReviewEntity review = getReview(reviewId);
        review.setVisibility(visibility);
        applyAdminAudit(review, reason, authentication);
        return mapToResponse(reviewRepository.save(review));
    }

    @Transactional
    public void deleteByAdmin(Long reviewId) {
        ProductReviewEntity review = getReview(reviewId);
        List<String> storageKeys = review.getImages().stream()
                .map(ProductReviewImageEntity::getStorageKey)
                .toList();
        reviewRepository.delete(review);
        storageKeys.forEach(key -> {
            try { mediaStorage.delete(key); }
            catch (IOException ex) { System.err.println("Review media cleanup failed: " + key); }
        });
    }

    private ProductReviewEntity getReview(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review không tồn tại"));
    }

    private void applyAdminAudit(ProductReviewEntity review, String reason, Authentication authentication) {
        review.setModerationReason(reason);
        review.setModeratedBy(((UserPrincipal) authentication.getPrincipal()).getUser().getId());
        review.setModeratedAt(OffsetDateTime.now());
        review.setUpdatedAt(review.getModeratedAt());
    }

    @Transactional
    public ReviewResponse create(ReviewRequest request, Authentication authentication,
                                 MultipartFile[] imageFiles) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        UUID userId = principal.getUser().getId();
        Purchase purchase = findCompletedPurchase(userId, request.getProductId());
        if (purchase == null) {
            throw new IllegalArgumentException("Bạn chỉ có thể đánh giá sản phẩm đã mua trong đơn hoàn tất");
        }

        MultipartFile[] reviewFiles = imageFiles == null ? new MultipartFile[0] : imageFiles;
        validateImages(reviewFiles);
        ProductReviewEntity entity = new ProductReviewEntity();
        entity.setProductId(request.getProductId());
        entity.setUser(principal.getUser());
        entity.setGuestName(principal.getUser().getFullName());
        entity.setGuestEmail(principal.getUser().getEmail());
        entity.setRating(request.getRating());
        entity.setComment(request.getComment());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setVisibility(ProductReviewVisibility.HIDDEN);
        entity.setOrderId(purchase.orderId());
        entity.setUpdatedAt(entity.getCreatedAt());

        List<String> storedKeys = new ArrayList<>();
        try {
            for (int index = 0; index < reviewFiles.length; index++) {
                MultipartFile file = reviewFiles[index];
                addMedia(entity, file, ReviewMediaType.IMAGE, index, storedKeys);
            }
            ProductReviewEntity saved = reviewRepository.saveAndFlush(entity);
            return mapToResponse(saved);
        } catch (IOException | RuntimeException ex) {
            storedKeys.forEach(key -> {
                try { mediaStorage.delete(key); } catch (IOException ignored) { }
            });
            throw new IllegalStateException("Không thể lưu đánh giá", ex);
        }
    }

    private Purchase findCompletedPurchase(UUID userId, String productId) {
        for (OrderEntity order : orderRepository.findByUserId(userId)) {
            if (!"completed".equalsIgnoreCase(order.getStatus()) || order.getItems() == null) continue;
            boolean containsProduct = order.getItems().stream()
                    .anyMatch(item -> productId.equals(item.getProductId()));
            if (containsProduct) return new Purchase(order.getId());
        }
        return null;
    }

    private void validateImages(MultipartFile[] files) {
        if (files.length > 3) throw new IllegalArgumentException("Tối đa 3 ảnh cho mỗi đánh giá");
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) throw new IllegalArgumentException("Ảnh đánh giá không hợp lệ");
            if (file.getSize() > 5 * 1024 * 1024) throw new IllegalArgumentException("Mỗi ảnh tối đa 5MB");
            detectAndValidateImage(file);
        }
    }

    private String detectAndValidateImage(MultipartFile file) {
        String originalFilename = file.getOriginalFilename() == null ? "ảnh" : file.getOriginalFilename();
        try {
            byte[] bytes = file.getBytes();
            String detectedMime;
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
                detectedMime = "image/jpeg";
            } else if (startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
                detectedMime = "image/png";
            } else if (startsWith(bytes, new byte[]{0x52, 0x49, 0x46, 0x46}) && bytes.length > 12 && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
                detectedMime = "image/webp";
            } else {
                throw new IllegalArgumentException("File " + originalFilename + " không có chữ ký JPG, PNG hoặc WebP hợp lệ");
            }

            return detectedMime;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Không thể đọc file " + originalFilename, ex);
        }
    }

    private boolean startsWith(byte[] actual, byte[] expected) {
        if (actual.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (actual[i] != expected[i]) return false;
        return true;
    }

    private void addMedia(ProductReviewEntity review, MultipartFile file, ReviewMediaType type, int order, List<String> storedKeys) throws IOException {
        String detectedMime = detectAndValidateImage(file);
        ReviewMediaStorage.StoredReviewMedia stored = mediaStorage.store(new ByteArrayInputStream(file.getBytes()), file.getOriginalFilename(), detectedMime, file.getSize());
        storedKeys.add(stored.storageKey());
        ProductReviewImageEntity media = new ProductReviewImageEntity();
        media.setStorageKey(stored.storageKey());
        media.setImageUrl(stored.mediaUrl());
        media.setMimeType(stored.mimeType());
        media.setByteSize(file.getSize());
        media.setChecksum(stored.checksum());
        media.setDisplayOrder(order);
        media.setMediaType(type);
        review.addImage(media);
    }

    private record Purchase(UUID orderId) { }

    private ReviewResponse mapToResponse(ProductReviewEntity entity) {
        ReviewResponse response = new ReviewResponse();
        response.setId(entity.getId());
        response.setProductId(entity.getProductId());
        response.setGuestName(entity.getGuestName());
        response.setGuestEmail(null);
        response.setRating(entity.getRating());
        response.setComment(entity.getComment());
        response.setCreatedAt(entity.getCreatedAt());
        response.setVisibility(entity.getVisibility() == null ? null : entity.getVisibility().name());
        response.setImages(entity.getImages().stream()
            .filter(media -> media.getMediaType() == null || media.getMediaType() == ReviewMediaType.IMAGE)
            .map(this::mapImage)
            .collect(Collectors.toList()));
        return response;
    }

    private ReviewImageResponse mapImage(ProductReviewImageEntity entity) {
        ReviewImageResponse response = new ReviewImageResponse();
        response.setId(entity.getId());
        response.setImageUrl(entity.getImageUrl());
        response.setMimeType(entity.getMimeType());
        response.setByteSize(entity.getByteSize());
        response.setDisplayOrder(entity.getDisplayOrder());
        return response;
    }
}
