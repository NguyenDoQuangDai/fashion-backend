package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.user.UserProfileResponse;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.*;
import com.example.fashion_backend.repository.OrderRepository;
import com.example.fashion_backend.repository.UserProductViewRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public class MetadataRecommendationBaseline {
    private final ProductService products;
    private final UserProfileService profiles;
    private final CartService carts;
    private final OrderRepository orders;
    private final UserProductViewRepository views;
    private final Clock clock;

    public MetadataRecommendationBaseline(ProductService products, UserProfileService profiles, CartService carts,
                                 OrderRepository orders, UserProductViewRepository views) {
        this(products, profiles, carts, orders, views, Clock.systemUTC());
    }

    MetadataRecommendationBaseline(ProductService products, UserProfileService profiles, CartService carts,
                          OrderRepository orders, UserProductViewRepository views, Clock clock) {
        this.products = products;
        this.profiles = profiles;
        this.carts = carts;
        this.orders = orders;
        this.views = views;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Product> recommend(Integer size, String gender, String tag, BigDecimal priceMin,
                                   BigDecimal priceMax, List<String> historyIds, UUID userId) {
        Assessment assessment = evaluate(size, gender, tag, priceMin, priceMax, historyIds, userId, false);
        return assessment.decisions().stream().filter(d -> d.exclusions().isEmpty()).limit(assessment.limit())
                .map(Decision::product).toList();
    }

    // Internal snapshot for the offline evaluation report. The public API remains List<Product>.
    Assessment assess(Integer size, String gender, String tag, BigDecimal priceMin,
                      BigDecimal priceMax, List<String> historyIds, UUID userId) {
        return evaluate(size, gender, tag, priceMin, priceMax, historyIds, userId, true);
    }

    private Assessment evaluate(Integer size, String gender, String tag, BigDecimal priceMin,
                                BigDecimal priceMax, List<String> historyIds, UUID userId, boolean explain) {
        if ((priceMin != null && priceMin.signum() < 0) || (priceMax != null && priceMax.signum() < 0)
                || (priceMin != null && priceMax != null && priceMin.compareTo(priceMax) > 0)) {
            throw new IllegalArgumentException("Invalid price range");
        }
        int limit = Math.min(Math.max(size == null ? 8 : size, 1), 40);
        Map<String, Product> catalog = products.getAllProducts().stream()
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(Product::getId, p -> p, (first, duplicate) -> first, LinkedHashMap::new));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (catalog.isEmpty()) return new Assessment(userId, now, null, Map.of(), Set.of(), Set.of(), .1, limit, List.of());
        UserProfileResponse profile = userId == null ? null : profiles.getProfile(userId);
        Map<String, Double> interests = new HashMap<>();
        Set<String> inCart = new HashSet<>();
        Set<String> recentlyPurchased = new HashSet<>();

        if (userId != null) {
            for (var view : views.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(userId, now.minusDays(30))) {
                addInterest(interests, catalog, view.getProductId(), decay(1, view.getLastViewedAt(), now, 7));
            }
            for (var item : carts.getCartItems(userId, null)) {
                if (item.getQuantity() > 0) {
                    inCart.add(item.getProductId());
                    addInterest(interests, catalog, item.getProductId(), 3);
                }
            }
            // Read existing orders only; no purchase events, counters or inferred completion timestamps.
            for (var order : orders.findByUserId(userId)) {
                if (!"completed".equalsIgnoreCase(order.getStatus()) || order.getItems() == null
                        || order.getPaymentStatus() == PaymentStatus.FAILED) continue;
                double weight = decay(5, order.getCreatedAt(), now, 60);
                for (var item : order.getItems()) {
                    addInterest(interests, catalog, item.getProductId(), weight);
                    if (order.getCreatedAt() != null && !order.getCreatedAt().isBefore(now.minusDays(30))) {
                        recentlyPurchased.add(item.getProductId());
                    }
                }
            }
        } else if (historyIds != null) {
            // Legacy guest hint, never used to select a user's private history.
            historyIds.stream().filter(Objects::nonNull).distinct().limit(50)
                    .forEach(id -> addInterest(interests, catalog, id, 1));
        }

        double maxPopularity = catalog.values().stream().mapToDouble(this::popularity).max().orElse(0);
        boolean hasProfile = hasPreferences(profile);
        double totalInterest = interests.values().stream().mapToDouble(Double::doubleValue).sum();
        double denominator = (hasProfile ? .30 : 0) + (totalInterest > 0 ? .60 : 0) + .10;

        List<Decision> decisions = catalog.values().stream()
                .map(p -> {
                    List<String> exclusions = new ArrayList<>();
                    if (!inStock(p)) exclusions.add("OUT_OF_STOCK");
                    if (inCart.contains(p.getId())) exclusions.add("IN_CART");
                    if (!normalize(gender).isEmpty() && !genderMatches(p.getGender(), gender)) exclusions.add("GENDER_FILTER");
                    if (!normalize(tag).isEmpty() && normalizedTags(p).stream().noneMatch(t -> t.contains(normalize(tag)))) exclusions.add("TAG_FILTER");
                    if (priceMin != null && (p.getPrice() == null || p.getPrice().compareTo(priceMin) < 0)) exclusions.add("PRICE_MIN_FILTER");
                    if (priceMax != null && (p.getPrice() == null || p.getPrice().compareTo(priceMax) > 0)) exclusions.add("PRICE_MAX_FILTER");
                    if (!explain && !exclusions.isEmpty()) return null;
                    double behavior = 0;
                    Map<String, Double> similarities = new LinkedHashMap<>();
                    for (var interest : interests.entrySet()) {
                        double similarity = affinity(p, catalog.get(interest.getKey()));
                        if (explain) similarities.put(interest.getKey(), similarity);
                        behavior += interest.getValue() * similarity;
                    }
                    if (totalInterest > 0) behavior /= totalInterest;
                    double profileScore = hasProfile ? profileAffinity(p, profile) : 0;
                    double popularityScore = maxPopularity > 0 ? popularity(p) / maxPopularity : 0;
                    double beforePenalty = (.30 * profileScore + .60 * behavior + .10 * popularityScore) / denominator;
                    double penalty = recentlyPurchased.contains(p.getId()) ? .25 : 1;
                    return new Decision(p, profileScore, behavior, popularityScore, beforePenalty, penalty,
                            beforePenalty * penalty, List.copyOf(exclusions), Map.copyOf(similarities));
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Decision d) -> !d.exclusions().isEmpty())
                        .thenComparing(Comparator.comparingDouble(Decision::score).reversed())
                        .thenComparing(Comparator.comparingInt((Decision sp) -> sp.product().getSoldCount()).reversed())
                        .thenComparing(sp -> sp.product().getId()))
                .toList();
        return new Assessment(userId, now, profile, Map.copyOf(interests), Set.copyOf(inCart),
                Set.copyOf(recentlyPurchased), denominator, limit, decisions);
    }

    private void addInterest(Map<String, Double> interests, Map<String, Product> catalog, String id, double weight) {
        if (catalog.containsKey(id) && weight > 0) interests.merge(id, weight, Math::max);
    }

    private double decay(double base, OffsetDateTime at, OffsetDateTime now, double halfLife) {
        if (at == null) return 0;
        double days = Math.max(0, Duration.between(at, now).toSeconds() / 86400.0);
        return base * Math.pow(.5, days / halfLife);
    }

    private boolean inStock(Product p) {
        return p.getColors() != null && p.getColors().stream().filter(Objects::nonNull)
                .anyMatch(c -> c.getSizes() != null && c.getSizes().stream().filter(Objects::nonNull)
                        .anyMatch(s -> s.isAvailable() && s.getInventory() > 0));
    }

    private double popularity(Product p) { return Math.log1p(Math.max(0, p.getSoldCount())); }

    private double affinity(Product candidate, Product source) {
        double score = 0, available = 0;
        if (!category(candidate).isEmpty() && !category(source).isEmpty()) {
            available += .45;
            if (category(candidate).equals(category(source))) score += .45;
        }
        if (!normalize(candidate.getType()).isEmpty() && !normalize(source.getType()).isEmpty()) {
            available += .25;
            if (normalize(candidate.getType()).equals(normalize(source.getType()))) score += .25;
        }
        Set<String> a = normalizedTags(candidate), b = normalizedTags(source);
        if (!a.isEmpty() && !b.isEmpty()) {
            available += .30;
            Set<String> union = new HashSet<>(a); union.addAll(b);
            score += .30 * a.stream().filter(b::contains).count() / union.size();
        }
        return available > 0 ? score / available : 0;
    }

    private boolean hasPreferences(UserProfileResponse p) {
        return p != null && (!normalize(p.getGender()).isEmpty() || p.getPriceMin() != null || p.getPriceMax() != null
                || (p.getPreferredCategories() != null && p.getPreferredCategories().stream().anyMatch(s -> !normalize(s).isEmpty())));
    }

    private double profileAffinity(Product product, UserProfileResponse p) {
        double score = 0, available = 0;
        if (p.getPreferredCategories() != null && p.getPreferredCategories().stream().anyMatch(s -> !normalize(s).isEmpty())) {
            available += .6;
            if (p.getPreferredCategories().stream().filter(s -> !normalize(s).isEmpty())
                    .anyMatch(s -> matchesPreference(product, s))) score += .6;
        }
        if (p.getPriceMin() != null || p.getPriceMax() != null) {
            available += .3;
            if (product.getPrice() != null && (p.getPriceMin() == null || product.getPrice().compareTo(p.getPriceMin()) >= 0)
                    && (p.getPriceMax() == null || product.getPrice().compareTo(p.getPriceMax()) <= 0)) score += .3;
        }
        if (!normalize(p.getGender()).isEmpty()) {
            available += .1;
            if (genderMatches(product.getGender(), p.getGender())) score += .1;
        }
        return available > 0 ? score / available : 0;
    }

    private String category(Product p) {
        return normalize(p.getCategorySlug()).isEmpty() ? normalize(p.getCategoryName()) : normalize(p.getCategorySlug());
    }

    private boolean matchesPreference(Product product, String selection) {
        String text = String.join(" ", Objects.toString(product.getTitle(), ""),
                Objects.toString(product.getCategoryName(), ""), Objects.toString(product.getCategorySlug(), ""),
                Objects.toString(product.getType(), ""), Objects.toString(product.getGender(), ""),
                product.getTags() == null ? "" : String.join(" ", product.getTags()));
        String base = normalize(selection).replaceFirst("\\s(nam|nu)$", "");
        if (matchesWords(text, "unisex")) {
            String type = normalize(product.getType()).isEmpty() ? product.getCategoryName() : product.getType();
            return matchesWords(text, base) || (!normalize(type).isEmpty() && matchesWords(base, type));
        }
        return matchesWords(text, selection);
    }

    private boolean matchesWords(String text, String query) {
        Set<String> words = new HashSet<>(Arrays.asList(normalize(text).split("[^\\p{L}\\p{N}]+")));
        List<String> terms = Arrays.stream(normalize(query).split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isEmpty()).toList();
        return !terms.isEmpty() && terms.stream().allMatch(words::contains);
    }

    private boolean genderMatches(String productGender, String preferred) {
        String value = normalize(productGender);
        return value.equals("unisex") || value.equals(normalize(preferred));
    }

    private Set<String> normalizedTags(Product p) {
        return p.getTags() == null ? Set.of() : p.getTags().stream().map(this::normalize)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("[-_\\s]+", " ").trim();
    }

    record Decision(Product product, double profileScore, double behaviorScore, double popularityScore,
                    double beforePenalty, double purchaseMultiplier, double score, List<String> exclusions,
                    Map<String, Double> sourceSimilarities) {}
    record Assessment(UUID userId, OffsetDateTime at, UserProfileResponse profile,
                      Map<String, Double> interests, Set<String> inCart, Set<String> recentlyPurchased,
                      double denominator, int limit, List<Decision> decisions) {}
}
