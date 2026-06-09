package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.ProductColorImageEntity;
import com.example.fashion_backend.entity.ProductColorVariantEntity;
import com.example.fashion_backend.entity.ProductEntity;
import com.example.fashion_backend.entity.ProductSizeVariantEntity;
import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.model.ProductColorVariant;
import com.example.fashion_backend.model.ProductSizeVariant;
import com.example.fashion_backend.repository.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminProductService {
    private final ProductRepository productRepository;

    public AdminProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts() {
        return productRepository.findAll().stream().map(this::mapToDto).toList();
    }

    @Transactional(readOnly = true)
    public Product getProduct(String id) {
        return productRepository.findById(id).map(this::mapToDto).orElse(null);
    }

    @Transactional
    public Product create(Product product) {
        ProductEntity entity = mapToEntity(product);
        return mapToDto(productRepository.save(entity));
    }

    @Transactional
    public Product update(String id, Product product) {
        ProductEntity existing = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        ProductEntity updated = mapToEntity(product);
        updated.setId(existing.getId());
        return mapToDto(productRepository.save(updated));
    }

    @Transactional
    public void delete(String id) {
        productRepository.deleteById(id);
    }

    private ProductEntity mapToEntity(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.setId(product.getId());
        entity.setSlug(product.getSlug());
        entity.setTitle(product.getTitle());
        entity.setBrand(product.getBrand());
        entity.setGender(product.getGender());
        entity.setCategoryName(product.getCategoryName());
        entity.setCategorySlug(product.getCategorySlug());
        entity.setType(null);
        entity.setShortDescription(product.getShortDescription());
        entity.setDescription(product.getDescription());
        entity.setCanonicalUrl(product.getCanonicalUrl());
        entity.setPrice(product.getPrice());
        entity.setPricePromotion(null);
        entity.setTags(product.getTags());
        entity.setCoverImage(product.getCoverImage());
        entity.setGeneralImages(product.getGeneralImages());
        entity.setSoldCount(product.getSoldCount());
        entity.setUrl(product.getUrl());

        List<ProductColorVariantEntity> colors = new ArrayList<>();
        if (product.getColors() != null) {
            int colorIndex = 0;
            for (ProductColorVariant color : product.getColors()) {
                ProductColorVariantEntity colorEntity = new ProductColorVariantEntity();
                colorEntity.setProduct(entity);
                colorEntity.setColorId(color.getColorId());
                colorEntity.setColorName(color.getColorName());
                colorEntity.setCoverImage(color.getCoverImage());
                colorEntity.setSortOrder(colorIndex++);

                Set<ProductColorImageEntity> images = new LinkedHashSet<>();
                if (color.getImages() != null) {
                    int imageIndex = 0;
                    for (String imageUrl : color.getImages()) {
                        ProductColorImageEntity imageEntity = new ProductColorImageEntity();
                        imageEntity.setColorVariant(colorEntity);
                        imageEntity.setImageUrl(imageUrl);
                        imageEntity.setSortOrder(imageIndex++);
                        images.add(imageEntity);
                    }
                }
                colorEntity.setImages(images);

                Set<ProductSizeVariantEntity> sizes = new LinkedHashSet<>();
                if (color.getSizes() != null) {
                    for (ProductSizeVariant size : color.getSizes()) {
                        ProductSizeVariantEntity sizeEntity = new ProductSizeVariantEntity();
                        sizeEntity.setColorVariant(colorEntity);
                        sizeEntity.setSizeId(size.getSizeId());
                        sizeEntity.setSizeName(size.getSize());
                        sizeEntity.setSku(size.getSku());
                        sizeEntity.setInventory(size.getInventory());
                        sizeEntity.setPrice(size.getPrice());
                        sizeEntity.setPricePromotion(null);
                        sizes.add(sizeEntity);
                    }
                }
                colorEntity.setSizes(sizes);
                colors.add(colorEntity);
            }
        }
        entity.setColors(colors);
        return entity;
    }

    private Product mapToDto(ProductEntity entity) {
        Product dto = new Product();
        dto.setId(entity.getId());
        dto.setSlug(entity.getSlug());
        dto.setTitle(entity.getTitle());
        dto.setBrand(entity.getBrand());
        dto.setGender(entity.getGender());
        dto.setCategoryName(entity.getCategoryName());
        dto.setCategorySlug(entity.getCategorySlug());
        dto.setShortDescription(entity.getShortDescription());
        dto.setDescription(entity.getDescription());
        dto.setCanonicalUrl(entity.getCanonicalUrl());
        dto.setPrice(entity.getPrice());
        dto.setTags(entity.getTags());
        dto.setCoverImage(entity.getCoverImage());
        dto.setGeneralImages(entity.getGeneralImages());
        dto.setSoldCount(entity.getSoldCount() == null ? 0 : entity.getSoldCount());
        dto.setUrl(entity.getUrl());

        List<ProductColorVariant> colors = mapColors(entity.getColors());
        dto.setColors(colors);
        return dto;
    }

    private List<ProductColorVariant> mapColors(List<ProductColorVariantEntity> colorEntities) {
        if (colorEntities == null || colorEntities.isEmpty()) return new ArrayList<>();
        Map<String, ProductColorVariantEntity> unique = new LinkedHashMap<>();
        int index = 0;
        for (ProductColorVariantEntity colorEntity : colorEntities) {
            String key = colorEntity.getId() != null ? "id:" + colorEntity.getId() : "cid:" + Objects.toString(colorEntity.getColorId(), "");
            if ("cid:".equals(key)) {
                key = "idx:" + index;
            }
            unique.putIfAbsent(key, colorEntity);
            index++;
        }
        return unique.values().stream().map(this::mapColor).toList();
    }

    private ProductColorVariant mapColor(ProductColorVariantEntity colorEntity) {
        ProductColorVariant color = new ProductColorVariant();
        color.setColorId(colorEntity.getColorId());
        color.setColorName(colorEntity.getColorName());
        color.setCoverImage(colorEntity.getCoverImage());
        color.setImages(colorEntity.getImages() == null ? List.of() : colorEntity.getImages().stream()
                .map(ProductColorImageEntity::getImageUrl)
                .toList());
        if (colorEntity.getSizes() != null) {
            color.setSizes(colorEntity.getSizes().stream().map(size -> {
                ProductSizeVariant dto = new ProductSizeVariant();
                dto.setSizeId(size.getSizeId());
                dto.setSize(size.getSizeName());
                dto.setInventory(size.getInventory() == null ? 0 : size.getInventory());
                dto.setSku(size.getSku());
                dto.setPrice(size.getPrice());
                dto.setAvailable(size.getInventory() != null && size.getInventory() > 0);
                return dto;
            }).toList());
        } else {
            color.setSizes(List.of());
        }
        return color;
    }
}
