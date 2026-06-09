package com.example.fashion_backend.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.util.List;

public class Product {
    private String id;
    private String slug;
    private String title;
    private String brand;
    private String gender;
    private String categoryName;
    private String categorySlug;
    private String shortDescription;
    private String description;
    private String canonicalUrl;
    private BigDecimal price;
    private List<String> tags;
    private String coverImage;
    @JsonAlias("images")
    private List<String> generalImages;
    private List<ProductColorVariant> colors;
    private int soldCount;
    private String url;

    public Product() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() {
        if (title != null && title.toLowerCase().contains("fashion")) {
            return title.replaceAll("(?i)Fashion", "").trim();
        }
        return title;
    }
    public void setTitle(String title) { this.title = title; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getCategorySlug() { return categorySlug; }
    public void setCategorySlug(String categorySlug) { this.categorySlug = categorySlug; }

    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }

    public String getDescription() {
        if (description != null) {
            String result = description.replace("Fashion", "Man");  
            result = result.replace("5S Man", "").trim();
            // Thêm xuống dòng sau mỗi dấu chấm
            result = result.replaceAll("\\.\\s*", ".\n");
            return result.trim();
        }
        return description;
    }
    public void setDescription(String description) { this.description = description; }

    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public List<String> getGeneralImages() { return generalImages; }
    public void setGeneralImages(List<String> generalImages) { this.generalImages = generalImages; }

    public List<ProductColorVariant> getColors() { return colors; }
    public void setColors(List<ProductColorVariant> colors) { this.colors = colors; }

    public int getSoldCount() { return soldCount; }
    public void setSoldCount(int soldCount) { this.soldCount = soldCount; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
