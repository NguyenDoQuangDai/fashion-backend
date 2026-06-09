package com.example.fashion_backend.model;

import java.util.List;

public class ProductColorVariant {
    private String colorId;
    private String colorName;
    private String coverImage;
    private List<String> images;
    private List<ProductSizeVariant> sizes;
    private boolean current;

    public ProductColorVariant() {}

    public String getColorId() { return colorId; }
    public void setColorId(String colorId) { this.colorId = colorId; }

    public String getColorName() { return colorName; }
    public void setColorName(String colorName) { this.colorName = colorName; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public List<ProductSizeVariant> getSizes() { return sizes; }
    public void setSizes(List<ProductSizeVariant> sizes) { this.sizes = sizes; }

    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}
