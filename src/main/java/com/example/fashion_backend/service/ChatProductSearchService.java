package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.example.fashion_backend.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/** Chat-specific filtering. Never relaxes constraints or changes the shared search API. */
@Service
public class ChatProductSearchService {
    private final ProductService products;
    public ChatProductSearchService(ProductService products) { this.products = products; }
    public record Match(ChatSuggestedProduct product, List<String> evidence, int softScore) {}
    public List<Match> search(ChatCriteria criteria) {
        return search(criteria, products.list(null, null, null, null, null, null));
    }
    public List<Match> search(ChatCriteria c, List<Product> catalog) {
        var matches = new ArrayList<Match>();
        for (Product p : catalog) {
            if (p == null || p.getId() == null || p.getSlug() == null || p.getTitle() == null) continue;
            if (c.category() != null && !c.category().equals(category(p))) continue;
            if (c.gender() != null && !ChatText.normalize(p.getGender()).equals(c.gender())) continue;
            String typeSource = Objects.toString(p.getCategoryName(), "") + " " + Objects.toString(p.getType(), "") + " " + p.getTitle();
            if (c.productType() != null && !ChatText.contains(typeSource, c.productType())) continue;
            Match best = null;
            for (ProductColorVariant color : safe(p.getColors())) {
                if (color == null) continue;
                if (!c.colorsInclude().isEmpty() && c.colorsInclude().stream().noneMatch(x -> ChatText.contains(color.getColorName(), x))) continue;
                if (c.colorsExclude().stream().anyMatch(x -> ChatText.contains(color.getColorName(), x))) continue;
                for (ProductSizeVariant size : safe(color.getSizes())) {
                    if (size == null || size.getInventory() <= 0) continue;
                    if (c.size() != null && !ChatText.normalize(c.size()).equals(ChatText.normalize(size.getSize()))) continue;
                    BigDecimal price = size.getPrice() != null ? size.getPrice() : p.getPrice();
                    if (price == null || price.signum() < 0 || !inPrice(price, c)) continue;
                    var card = new ChatSuggestedProduct();
                    card.setId(p.getId()); card.setSlug(p.getSlug()); card.setTitle(p.getTitle()); card.setPrice(price);
                    card.setCoverImage(image(p, color)); card.setMatchedColor(color.getColorName()); card.setMatchedSize(size.getSize());
                    var evidence = new ArrayList<String>();
                    if (c.priceMin() != null || c.priceMax() != null) evidence.add("budget");
                    if (!c.colorsInclude().isEmpty() || !c.colorsExclude().isEmpty()) evidence.add("color");
                    if (c.size() != null) evidence.add("size");
                    int score = 0;
                    String description = Objects.toString(p.getDescription(), "") + " " + Objects.toString(p.getShortDescription(), "") + " " + String.join(" ", safe(p.getTags()));
                    for (String material : c.materialPreferences()) if (ChatText.contains(description, material)) score++;
                    if (score > 0) evidence.add("material");
                    String occasion = c.occasion() == null ? "" : c.occasion().replace('_', ' ');
                    if (!occasion.isEmpty() && ChatText.contains(description, occasion)) { evidence.add("occasion"); score++; }
                    var candidate = new Match(card, List.copyOf(evidence), score);
                    if (best == null || price.compareTo(best.product().getPrice()) < 0) best = candidate;
                }
            }
            if (best != null) matches.add(best);
        }
        Comparator<Match> comparator = switch (c.sort()) {
            case "price_asc" -> Comparator.comparing(x -> x.product().getPrice());
            case "price_desc" -> Comparator.<Match, BigDecimal>comparing(x -> x.product().getPrice()).reversed();
            default -> Comparator.comparingInt(Match::softScore).reversed();
        };
        return matches.stream().sorted(comparator.thenComparing(x -> x.product().getId())).limit(c.quantity()).toList();
    }
    private boolean inPrice(BigDecimal price, ChatCriteria c) {
        if (c.priceMin() != null) {
            int cmp = price.compareTo(BigDecimal.valueOf(c.priceMin()));
            if (cmp < 0 || (cmp == 0 && !c.priceMinInclusive())) return false;
        }
        if (c.priceMax() != null) {
            int cmp = price.compareTo(BigDecimal.valueOf(c.priceMax()));
            if (cmp > 0 || (cmp == 0 && !c.priceMaxInclusive())) return false;
        }
        return true;
    }
    private String category(Product p) {
        String value = p.getCategoryName();
        if (value == null || value.isBlank()) value = p.getTitle();
        if (ChatText.contains(value, "bo")) return "bo";
        if (ChatText.contains(value, "vay") || ChatText.contains(value, "dam")) return "vay";
        if (ChatText.contains(value, "quan")) return "quan";
        if (ChatText.contains(value, "ao")) return "ao";
        return "phu_kien";
    }
    private String image(Product p, ProductColorVariant c) {
        if (c.getCoverImage() != null && !c.getCoverImage().isBlank()) return c.getCoverImage();
        if (!safe(c.getImages()).isEmpty()) return c.getImages().get(0);
        if (p.getCoverImage() != null) return p.getCoverImage();
        return safe(p.getGeneralImages()).isEmpty() ? null : p.getGeneralImages().get(0);
    }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
