package com.example.fashion_backend.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class ProductEntity {
    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "slug", unique = true)
    private String slug;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "brand")
    private String brand;

    @Column(name = "gender")
    private String gender;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "category_slug")
    private String categorySlug;

    @Column(name = "type")
    private String type;

    @Column(name = "short_description")
    private String shortDescription;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "canonical_url")
    private String canonicalUrl;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "price_promotion")
    private BigDecimal pricePromotion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "cover_image")
    private String coverImage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "general_images", columnDefinition = "jsonb")
    private List<String> generalImages;

    @Column(name = "sold_count")
    private Integer soldCount;

    @Column(name = "url")
    private String url;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductColorVariantEntity> colors;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getCategorySlug() { return categorySlug; }
    public void setCategorySlug(String categorySlug) { this.categorySlug = categorySlug; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getPricePromotion() { return pricePromotion; }
    public void setPricePromotion(BigDecimal pricePromotion) { this.pricePromotion = pricePromotion; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public List<String> getGeneralImages() { return generalImages; }
    public void setGeneralImages(List<String> generalImages) { this.generalImages = generalImages; }

    public Integer getSoldCount() { return soldCount; }
    public void setSoldCount(Integer soldCount) { this.soldCount = soldCount; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public List<ProductColorVariantEntity> getColors() { return colors; }
    public void setColors(List<ProductColorVariantEntity> colors) { this.colors = colors; }
}
