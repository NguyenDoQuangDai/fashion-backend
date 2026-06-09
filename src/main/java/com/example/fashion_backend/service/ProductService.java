package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.ProductColorImageEntity;
import com.example.fashion_backend.entity.ProductColorVariantEntity;
import com.example.fashion_backend.entity.ProductEntity;
import com.example.fashion_backend.entity.ProductSizeVariantEntity;
import com.example.fashion_backend.model.Product;
import com.example.fashion_backend.model.ProductColorVariant;
import com.example.fashion_backend.model.ProductSizeVariant;
import com.example.fashion_backend.repository.ProductRepository;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @PostConstruct
    public void init() {
        try {
            System.out.println("ProductService initialized with live database access");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Product> list(Integer page, Integer size, String gender, String category, String tag, String type) {
        List<Product> source = getAllProducts().stream()
                .filter(p -> matchesGender(p, gender))
                .filter(p -> matchesCategory(p, category, type))
                .filter(p -> matchesTag(p, tag))
                .collect(Collectors.toList());
        if (page != null && size != null) {
            int from = page * size;
            if (from >= source.size()) return new ArrayList<>();
            int to = Math.min(from + size, source.size());
            return source.subList(from, to);
        }
        return new ArrayList<>(source);
    }

    public Product findById(String id) {
        return productRepository.findById(id).map(this::mapToDto).orElse(null);
    }

    public List<Product> search(String q, BigDecimal priceMin, BigDecimal priceMax, String gender, String category, String tag, String type) {
        String qq = normalize(Objects.requireNonNullElse(q, ""));
        return getAllProducts().stream()
                .filter(p -> qq.isEmpty() ||
                        (p.getTitle() != null && normalize(p.getTitle()).contains(qq)) ||
                        (p.getDescription() != null && normalize(p.getDescription()).contains(qq)))
                .filter(p -> priceMin == null || p.getPrice() == null || p.getPrice().compareTo(priceMin) >= 0)
                .filter(p -> priceMax == null || p.getPrice() == null || p.getPrice().compareTo(priceMax) <= 0)
                .filter(p -> matchesGender(p, gender))
                .filter(p -> matchesCategory(p, category, type))
                .filter(p -> matchesTag(p, tag))
                .collect(Collectors.toList());
    }

    public List<Product> similarByTags(String productId, int size) {
        Product current = findById(productId);
        if (current == null) return new ArrayList<>();

        List<String> currentTags = current.getTags() == null ? new ArrayList<>() :
                current.getTags().stream().map(this::normalize).collect(Collectors.toList());

        // If no tags, fallback to category/type matching
        List<Product> byTag = new ArrayList<>();
        if (!currentTags.isEmpty()) {
            // Score products by tag overlap
            byTag = getAllProducts().stream()
                    .filter(p -> !p.getId().equals(productId))
                    .map(p -> new Object() {
                        final Product prod = p;
                        final long score = p.getTags() == null ? 0L : p.getTags().stream()
                                .map(t -> normalize(t))
                                .filter(currentTags::contains)
                                .count();
                    })
                    .filter(x -> x.score > 0)
                    .sorted((a, b) -> Long.compare(b.score, a.score))
                    .map(x -> x.prod)
                    .collect(Collectors.toList());
        }

        // If not enough results, add category/type matches (preserve order and de-dup)
        List<Product> result = new ArrayList<>();
        for (Product p : byTag) if (result.size() < size) result.add(p);

        if (result.size() < size) {
            List<Product> byCategory = getAllProducts().stream()
                    .filter(p -> !p.getId().equals(productId))
                    .filter(p -> matchesCategory(p, current.getCategorySlug(), null) || matchesCategory(p, current.getCategoryName(), null))
                    .filter(p -> !result.contains(p))
                    .collect(Collectors.toList());
            for (Product p : byCategory) {
                if (result.size() >= size) break;
                result.add(p);
            }
        }

        // Final fallback: fill with any suggested products
        if (result.size() < size) {
            for (Product p : getAllProducts()) {
                if (result.size() >= size) break;
                if (!p.getId().equals(productId) && !result.contains(p)) result.add(p);
            }
        }

        return result.size() > size ? result.subList(0, size) : result;
    }

    public List<Product> suggested(int size) {
        List<Product> all = getAllProducts();
        int take = Math.min(Math.max(size, 1), all.size());
        return new ArrayList<>(all.subList(0, take));
    }

    public List<Product> getAllProducts() {
        List<ProductEntity> entities = productRepository.findAll();
        return entities.stream().map(this::mapToDto).collect(Collectors.toList());
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

    private List<ProductColorVariant> mapColors(Collection<ProductColorVariantEntity> colorEntities) {
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
        return unique.values().stream().map(this::mapColor).collect(Collectors.toList());
    }

    private ProductColorVariant mapColor(ProductColorVariantEntity colorEntity) {
        ProductColorVariant color = new ProductColorVariant();
        color.setColorId(colorEntity.getColorId());
        color.setColorName(colorEntity.getColorName());
        color.setCoverImage(colorEntity.getCoverImage());
        color.setImages(mapImages(colorEntity.getImages()));
        color.setSizes(mapSizes(colorEntity.getSizes()));
        return color;
    }

    private List<String> mapImages(Collection<ProductColorImageEntity> imageEntities) {
        if (imageEntities == null) return new ArrayList<>();
        return imageEntities.stream().map(ProductColorImageEntity::getImageUrl).collect(Collectors.toList());
    }

    private List<ProductSizeVariant> mapSizes(Collection<ProductSizeVariantEntity> sizeEntities) {
        if (sizeEntities == null) return new ArrayList<>();
        List<ProductSizeVariant> sizes = new ArrayList<>();
        for (ProductSizeVariantEntity entity : sizeEntities) {
            ProductSizeVariant size = new ProductSizeVariant();
            size.setSizeId(entity.getSizeId());
            size.setSize(entity.getSizeName());
            size.setInventory(entity.getInventory() == null ? 0 : entity.getInventory());
            size.setSku(entity.getSku());
            size.setPrice(entity.getPrice());
            size.setAvailable(entity.getInventory() != null && entity.getInventory() > 0);
            sizes.add(size);
        }
        return sizes;
    }

    private boolean matchesGender(Product p, String gender) {
        if (gender == null || gender.isBlank()) return true;
        return normalize(Objects.requireNonNullElse(p.getGender(), "")).contains(normalize(gender));
    }

    private boolean matchesCategory(Product p, String category, String type) {
        String effective = Optional.ofNullable(category).filter(s -> !s.isBlank()).orElse(type);
        if (effective == null || effective.isBlank()) return true;
        String normalized = normalize(effective);
        return normalize(Objects.requireNonNullElse(p.getCategoryName(), "")).contains(normalized)
                || normalize(Objects.requireNonNullElse(p.getCategorySlug(), "")).contains(normalized)
                || normalize(Objects.requireNonNullElse(p.getTitle(), "")).contains(normalized);
    }

    private boolean matchesTag(Product p, String tag) {
        if (tag == null || tag.isBlank()) return true;
        String normalized = normalize(tag);
        return p.getTags() != null && p.getTags().stream().anyMatch(t -> normalize(t).contains(normalized));
    }

    private String normalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }
}
