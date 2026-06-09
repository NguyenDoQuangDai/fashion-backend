package com.example.fashion_backend.model;

public class CartItem {
    private String productId;
    private String colorId;
    private String colorName;
    private String sizeId;
    private String sizeName;
    private int quantity;

    public CartItem() {}

    public CartItem(String productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getColorId() { return colorId; }
    public void setColorId(String colorId) { this.colorId = colorId; }

    public String getColorName() { return colorName; }
    public void setColorName(String colorName) { this.colorName = colorName; }

    public String getSizeId() { return sizeId; }
    public void setSizeId(String sizeId) { this.sizeId = sizeId; }

    public String getSizeName() { return sizeName; }
    public void setSizeName(String sizeName) { this.sizeName = sizeName; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
