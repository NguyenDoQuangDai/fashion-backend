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
@Table(name = "order_items")
public class OrderItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "title_snapshot")
    private String titleSnapshot;

    @Column(name = "color_id")
    private String colorId;

    @Column(name = "color_snapshot")
    private String colorSnapshot;

    @Column(name = "size_id")
    private String sizeId;

    @Column(name = "size_snapshot")
    private String sizeSnapshot;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "price_snapshot")
    private BigDecimal priceSnapshot;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public OrderEntity getOrder() { return order; }
    public void setOrder(OrderEntity order) { this.order = order; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getTitleSnapshot() { return titleSnapshot; }
    public void setTitleSnapshot(String titleSnapshot) { this.titleSnapshot = titleSnapshot; }

    public String getColorId() { return colorId; }
    public void setColorId(String colorId) { this.colorId = colorId; }

    public String getColorSnapshot() { return colorSnapshot; }
    public void setColorSnapshot(String colorSnapshot) { this.colorSnapshot = colorSnapshot; }

    public String getSizeId() { return sizeId; }
    public void setSizeId(String sizeId) { this.sizeId = sizeId; }

    public String getSizeSnapshot() { return sizeSnapshot; }
    public void setSizeSnapshot(String sizeSnapshot) { this.sizeSnapshot = sizeSnapshot; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getPriceSnapshot() { return priceSnapshot; }
    public void setPriceSnapshot(BigDecimal priceSnapshot) { this.priceSnapshot = priceSnapshot; }
}
