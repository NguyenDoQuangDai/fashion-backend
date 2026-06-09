package com.example.fashion_backend.model;

import java.math.BigDecimal;

public class ProductSizeVariant {
    private String sizeId;
    private String size;
    private int inventory;
    private String sku;
    private BigDecimal price;
    private boolean available;

    public ProductSizeVariant() {}

    public String getSizeId() { return sizeId; }
    public void setSizeId(String sizeId) { this.sizeId = sizeId; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public int getInventory() { return inventory; }
    public void setInventory(int inventory) { this.inventory = inventory; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}
