package com.example.fashion_backend.entity;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_size_variants")
public class ProductSizeVariantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_variant_id")
    private ProductColorVariantEntity colorVariant;

    @Column(name = "size_id")
    private String sizeId;

    @Column(name = "size_name")
    private String sizeName;

    @Column(name = "sku")
    private String sku;

    @Column(name = "inventory")
    private Integer inventory;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "price_promotion")
    private BigDecimal pricePromotion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ProductColorVariantEntity getColorVariant() { return colorVariant; }
    public void setColorVariant(ProductColorVariantEntity colorVariant) { this.colorVariant = colorVariant; }

    public String getSizeId() { return sizeId; }
    public void setSizeId(String sizeId) { this.sizeId = sizeId; }

    public String getSizeName() { return sizeName; }
    public void setSizeName(String sizeName) { this.sizeName = sizeName; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public Integer getInventory() { return inventory; }
    public void setInventory(Integer inventory) { this.inventory = inventory; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getPricePromotion() { return pricePromotion; }
    public void setPricePromotion(BigDecimal pricePromotion) { this.pricePromotion = pricePromotion; }
}
