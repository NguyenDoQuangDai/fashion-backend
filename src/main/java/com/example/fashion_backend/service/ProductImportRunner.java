package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.ProductColorImageEntity;
import com.example.fashion_backend.entity.ProductColorVariantEntity;
import com.example.fashion_backend.entity.ProductEntity;
import com.example.fashion_backend.entity.ProductSizeVariantEntity;
import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.model.ProductColorVariant;
import com.example.fashion_backend.model.ProductSizeVariant;
import com.example.fashion_backend.repository.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ProductImportRunner implements CommandLineRunner {
    private final ProductRepository productRepository;

    public ProductImportRunner(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (productRepository.count() > 0) {
            return;
        }

        ClassPathResource resource = new ClassPathResource("static/data/products.json");
        if (!resource.exists()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try (InputStream is = resource.getInputStream()) {
            List<Product> products = mapper.readValue(is, new TypeReference<List<Product>>() {});
            if (products == null || products.isEmpty()) {
                return;
            }
            List<ProductEntity> entities = new ArrayList<>();
            for (Product product : products) {
                entities.add(mapProduct(product));
            }
            productRepository.saveAll(entities);
        }
    }

    private ProductEntity mapProduct(Product product) {
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

        List<ProductColorVariantEntity> colorEntities = new ArrayList<>();
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
                colorEntities.add(colorEntity);
            }
        }
        entity.setColors(colorEntities);
        return entity;
    }
}
