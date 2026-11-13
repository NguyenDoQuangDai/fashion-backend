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
    @org.springframework.beans.factory.annotation.Autowired private InventoryService inventory;

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
        if (productRepository.existsById(product.getId())) throw new IllegalArgumentException("Product already exists");
        ProductEntity entity = mapToEntity(product);
        java.util.Map<ProductSizeVariantEntity,Integer> initial = new java.util.LinkedHashMap<>();
        entity.getColors().forEach(c -> c.getSizes().forEach(v -> { initial.put(v,v.getInventory()); v.setInventory(0); }));
        inventory.persistNew(entity);
        inventory.lockProducts(List.of(entity.getId()));
        var sizes = inventory.lockSizes(initial.keySet().stream().map(ProductSizeVariantEntity::getId).toList());
        initial.forEach((v,q) -> inventory.change(sizes.get(v.getId()), "admin-init:" + v.getId(), "INITIALIZE", q));
        return mapToDto(entity);
    }

    @Transactional
    public Product update(String id, Product product) {
        inventory.lockProducts(List.of(id));
        ProductEntity existing = productRepository.findById(id).orElseThrow();
        ProductEntity input = mapToEntity(product);
        var locked = inventory.lockSizes(existing.getColors().stream().flatMap(c -> c.getSizes().stream()).map(ProductSizeVariantEntity::getId).toList());
        if (input.getColors().size() != existing.getColors().size()) throw new IllegalArgumentException("Structural variant edits require separate review");
        for (var c : existing.getColors()) {
            var incoming = input.getColors().stream().filter(v -> Objects.equals(v.getColorId(),c.getColorId())).toList();
            if (incoming.size()!=1 || incoming.get(0).getSizes().size()!=c.getSizes().size()) throw new IllegalArgumentException("Variant identity changed");
            var ci = incoming.get(0);
            for (var v : c.getSizes()) {
                var matches = ci.getSizes().stream().filter(x -> Objects.equals(x.getSizeId(),v.getSizeId())).toList();
                if(matches.size()!=1) throw new IllegalArgumentException("Size identity changed");
                var vi = matches.get(0);
                var dto = product.getColors().stream().filter(x -> Objects.equals(x.getColorId(),c.getColorId())).findFirst().orElseThrow()
                    .getSizes().stream().filter(x -> Objects.equals(x.getSizeId(),v.getSizeId())).findFirst().orElseThrow();
                inventory.adminSet(locked.get(v.getId()),vi.getInventory(),dto.getInventoryRevision());
                v.setSizeName(vi.getSizeName()); v.setSku(vi.getSku()); v.setPrice(vi.getPrice());
            }
            c.setColorName(ci.getColorName()); c.setCoverImage(ci.getCoverImage());
            c.getImages().clear();
            ci.getImages().forEach(image -> { image.setColorVariant(c); c.getImages().add(image); });
        }
        existing.setSlug(input.getSlug()); existing.setTitle(input.getTitle()); existing.setBrand(input.getBrand());
        existing.setGender(input.getGender()); existing.setCategoryName(input.getCategoryName()); existing.setCategorySlug(input.getCategorySlug());
        existing.setShortDescription(input.getShortDescription()); existing.setDescription(input.getDescription());
        existing.setCanonicalUrl(input.getCanonicalUrl()); existing.setPrice(input.getPrice()); existing.setTags(input.getTags());
        existing.setCoverImage(input.getCoverImage()); existing.setGeneralImages(input.getGeneralImages()); existing.setUrl(input.getUrl());
        return mapToDto(existing);
    }

    @Transactional
    public void delete(String id) {
        inventory.lockProducts(List.of(id));
        throw new IllegalArgumentException("Physical product deletion requires historical-reference review");
    }

    private ProductEntity mapToEntity(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.setCreatedAt(java.time.OffsetDateTime.now());
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
                dto.setInventoryRevision(size.getInventoryRevision());
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
