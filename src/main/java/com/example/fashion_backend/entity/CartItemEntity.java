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
@Table(name = "cart_items")
public class CartItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private CartEntity cart;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "color_variant_id")
    private Long colorVariantId;

    @Column(name = "size_variant_id")
    private Long sizeVariantId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "price_snapshot")
    private BigDecimal priceSnapshot;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public CartEntity getCart() { return cart; }
    public void setCart(CartEntity cart) { this.cart = cart; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public Long getColorVariantId() { return colorVariantId; }
    public void setColorVariantId(Long colorVariantId) { this.colorVariantId = colorVariantId; }

    public Long getSizeVariantId() { return sizeVariantId; }
    public void setSizeVariantId(Long sizeVariantId) { this.sizeVariantId = sizeVariantId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getPriceSnapshot() { return priceSnapshot; }
    public void setPriceSnapshot(BigDecimal priceSnapshot) { this.priceSnapshot = priceSnapshot; }
}
