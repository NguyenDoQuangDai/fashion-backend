package com.example.fashion_backend.service;

import com.example.fashion_backend.model.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final ProductService productService;

    public RecommendationService(ProductService productService) {
        this.productService = productService;
    }

    public List<Product> recommend(Integer size,
                                   String gender,
                                   String tag,
                                   BigDecimal priceMin,
                                   BigDecimal priceMax,
                                   List<String> historyIds) {
        int limit = Math.min(Math.max(size == null ? 8 : size, 1), 40);
        List<Product> products = new ArrayList<>(productService.getAllProducts());
        if (products.isEmpty()) return List.of();

        Set<String> historyTags = buildHistoryTags(products, historyIds);
        String normalizedTag = normalize(tag);

        List<ScoredProduct> scored = products.stream()
                .filter(p -> gender == null || gender.isBlank() ||
                        normalize(p.getGender()).contains(normalize(gender)))
                .filter(p -> priceMin == null || p.getPrice() == null || p.getPrice().compareTo(priceMin) >= 0)
                .filter(p -> priceMax == null || p.getPrice() == null || p.getPrice().compareTo(priceMax) <= 0)
                .filter(p -> normalizedTag.isEmpty() ||
                        (p.getTags() != null && p.getTags().stream().anyMatch(t -> normalize(t).contains(normalizedTag))))
                .map(p -> new ScoredProduct(p, score(p, historyTags, gender)))
                .sorted(Comparator.comparingLong(ScoredProduct::score).reversed()
                        .thenComparingLong(sp -> sp.product().getSoldCount()).reversed())
                .collect(Collectors.toList());

        return scored.stream().limit(limit).map(ScoredProduct::product).collect(Collectors.toList());
    }

    private long score(Product product, Set<String> historyTags, String gender) {
        long score = 0;
        if (historyTags != null && product.getTags() != null) {
            long overlap = product.getTags().stream()
                    .map(this::normalize)
                    .filter(historyTags::contains)
                    .count();
            score += overlap * 3;
        }
        if (gender != null && !gender.isBlank() && product.getGender() != null) {
            if (normalize(product.getGender()).contains(normalize(gender))) {
                score += 2;
            }
        }
        return score;
    }

    private Set<String> buildHistoryTags(List<Product> products, List<String> historyIds) {
        if (historyIds == null || historyIds.isEmpty()) return Set.of();
        Set<String> ids = new HashSet<>(historyIds);
        Set<String> tags = new HashSet<>();
        for (Product product : products) {
            if (ids.contains(product.getId()) && product.getTags() != null) {
                product.getTags().forEach(tag -> tags.add(normalize(tag)));
            }
        }
        return tags;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String normalized = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").trim();
    }

    private record ScoredProduct(Product product, long score) {}
}
