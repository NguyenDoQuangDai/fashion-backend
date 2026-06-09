package com.example.fashion_backend.entity;

import java.util.Set;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_color_variants")
public class ProductColorVariantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private ProductEntity product;

    @Column(name = "color_id")
    private String colorId;

    @Column(name = "color_name")
    private String colorName;

    @Column(name = "color_code")
    private String colorCode;

    @Column(name = "cover_image")
    private String coverImage;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @OneToMany(mappedBy = "colorVariant", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private Set<ProductColorImageEntity> images;

    @OneToMany(mappedBy = "colorVariant", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private Set<ProductSizeVariantEntity> sizes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ProductEntity getProduct() { return product; }
    public void setProduct(ProductEntity product) { this.product = product; }

    public String getColorId() { return colorId; }
    public void setColorId(String colorId) { this.colorId = colorId; }

    public String getColorName() { return colorName; }
    public void setColorName(String colorName) { this.colorName = colorName; }

    public String getColorCode() { return colorCode; }
    public void setColorCode(String colorCode) { this.colorCode = colorCode; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Set<ProductColorImageEntity> getImages() { return images; }
    public void setImages(Set<ProductColorImageEntity> images) { this.images = images; }

    public Set<ProductSizeVariantEntity> getSizes() { return sizes; }
    public void setSizes(Set<ProductSizeVariantEntity> sizes) { this.sizes = sizes; }
}
