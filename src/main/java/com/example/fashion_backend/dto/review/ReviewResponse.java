package com.example.fashion_backend.dto.review;

import java.time.OffsetDateTime;
import java.util.List;

public class ReviewResponse {
    private Long id;
    private String productId;
    private String guestName;
    private String guestEmail;
    private Integer rating;
    private String comment;
    private OffsetDateTime createdAt;
    private String visibility;
    private List<ReviewImageResponse> images;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public List<ReviewImageResponse> getImages() { return images; }
    public void setImages(List<ReviewImageResponse> images) { this.images = images; }
}
