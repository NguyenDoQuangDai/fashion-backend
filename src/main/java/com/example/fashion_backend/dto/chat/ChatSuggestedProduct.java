package com.example.fashion_backend.dto.chat;

import java.math.BigDecimal;

public class ChatSuggestedProduct {
    private String id;
    private String title;
    private String slug;
    private BigDecimal price;
    private String coverImage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }
}
