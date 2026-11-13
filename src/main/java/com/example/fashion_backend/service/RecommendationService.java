package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.user.UserProfileResponse;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.*;
import com.example.fashion_backend.repository.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    static final double ACTIVATION_THRESHOLD = 3;
    static final int PURCHASE_HISTORY_DAYS = 360;
    private final ProductService products;
    private final UserProfileService profiles;
    private final CartService carts;
    private final OrderRepository orders;
    private final UserProductViewRepository views;
    private final ProductSimilarityIndex index;
    private final Clock clock;

    @Autowired
    public RecommendationService(ProductService products, UserProfileService profiles, CartService carts,
                                 OrderRepository orders, UserProductViewRepository views, ProductSimilarityIndex index) {
        this(products, profiles, carts, orders, views, index, Clock.systemUTC());
    }
    RecommendationService(ProductService products, UserProfileService profiles, CartService carts,
                          OrderRepository orders, UserProductViewRepository views, ProductSimilarityIndex index, Clock clock) {
        this.products=products; this.profiles=profiles; this.carts=carts; this.orders=orders;
        this.views=views; this.index=index; this.clock=clock;
    }

    @Transactional(readOnly = true)
    public List<Product> recommend(Integer size, String gender, String tag, BigDecimal min,
                                   BigDecimal max, List<String> historyIds, UUID userId) {
        var result = evaluate(size, gender, tag, min, max, historyIds, userId, false);
        return result.decisions().stream().filter(d -> d.exclusions().isEmpty()).limit(result.limit()).map(Decision::product).toList();
    }
    Assessment assess(Integer size, String gender, String tag, BigDecimal min, BigDecimal max, List<String> historyIds, UUID userId) {
        return evaluate(size, gender, tag, min, max, historyIds, userId, true);
    }

    private Assessment evaluate(Integer size, String gender, String tag, BigDecimal min, BigDecimal max,
                                List<String> historyIds, UUID userId, boolean explain) {
        if ((min != null && min.signum()<0) || (max != null && max.signum()<0)
                || (min != null && max != null && min.compareTo(max)>0)) throw new IllegalArgumentException("Invalid price range");
        int limit=Math.min(40, Math.max(1, size==null?8:size));
        var catalog=products.getAllProducts().stream().filter(p -> p.getId()!=null)
                .collect(Collectors.toMap(Product::getId, p->p, (a,b)->a, LinkedHashMap::new));
        OffsetDateTime now=OffsetDateTime.now(clock);
        UserProfileResponse profile=userId==null?null:profiles.getProfile(userId);
        Map<String,Double> raw=new TreeMap<>(), usable=new TreeMap<>();
        Set<String> inCart=new HashSet<>();
        Map<String,String> recentPurchases=new TreeMap<>();
        List<Signal> signals=new ArrayList<>();
        if (userId!=null) {
            for (var v:views.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(userId,now.minusDays(30).minusNanos(1))) {
                collect(v.getProductId(), "VIEW", v.getLastViewedAt(), 1, decay(1,v.getLastViewedAt(),now,7),
                        within(v.getLastViewedAt(),now,30)?null:"VIEW_OUTSIDE_WINDOW", null,catalog,raw,usable,signals,explain);
            }
            for (var item:carts.getCartItems(userId,null)) {
                if (item.getQuantity()>0) {
                    inCart.add(item.getProductId());
                    collect(item.getProductId(),"CART",null,5,5,null,null,catalog,raw,usable,signals,explain);
                }
            }
            for (var order:orders.findByUserId(userId)) {
                String reason=!"completed".equalsIgnoreCase(order.getStatus())?"ORDER_NOT_COMPLETED":
                        order.getPaymentStatus()==PaymentStatus.FAILED?"PAYMENT_FAILED":
                        !within(order.getCreatedAt(),now,PURCHASE_HISTORY_DAYS)?"PURCHASE_OUTSIDE_WINDOW":null;
                if (order.getItems()==null) continue;
                for (var item:order.getItems()) {
                    String itemReason=item.getQuantity()==null || item.getQuantity()<=0?"INVALID_QUANTITY":reason;
                    collect(item.getProductId(),"PURCHASE",order.getCreatedAt(),10,decay(10,order.getCreatedAt(),now,60),
                            itemReason,Objects.toString(order.getId(),""),catalog,raw,usable,signals,explain);
                    if (itemReason==null && within(order.getCreatedAt(),now,30))
                        recentPurchases.put(item.getProductId(),Objects.toString(order.getId(),""));
                }
            }
        } else if (historyIds!=null) {
            historyIds.stream().filter(Objects::nonNull).distinct().limit(50).forEach(id ->
                    collect(id,"GUEST_HINT",null,1,1,null,null,catalog,raw,usable,signals,explain));
        }
        // TreeMap makes the sum order deterministic; never round before the gate or ranking.
        Double strength=index.isAvailable()?usable.values().stream().mapToDouble(Double::doubleValue).sum():null;
        double rawStrength=raw.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean activated=strength!=null && strength>=ACTIVATION_THRESHOLD;
        List<Decision> decisions=new ArrayList<>();
        for (Product p:catalog.values()) {
            List<String> exclusions=new ArrayList<>();
            if (!inStock(p)) exclusions.add("OUT_OF_STOCK");
            if (inCart.contains(p.getId())) exclusions.add("IN_CART");
            if (!normalize(gender).isEmpty() && !genderMatches(p.getGender(),gender)) exclusions.add("GENDER_FILTER");
            if (!normalize(tag).isEmpty() && normalizedTags(p).stream().noneMatch(t -> t.contains(normalize(tag)))) exclusions.add("TAG_FILTER");
            if (min!=null && (p.getPrice()==null || p.getPrice().compareTo(min)<0)) exclusions.add("PRICE_MIN_FILTER");
            if (max!=null && (p.getPrice()==null || p.getPrice().compareTo(max)>0)) exclusions.add("PRICE_MAX_FILTER");
            if (!explain && !exclusions.isEmpty()) continue;
            Double behavior=null;
            Map<String,Double> similarities=new LinkedHashMap<>();
            if (activated && index.contains(p.getId())) {
                double numerator=0;
                for (var source:usable.entrySet()) {
                    double similarity=index.similarity(source.getKey(),p.getId());
                    numerator+=source.getValue()*similarity;
                    if (explain) similarities.put(source.getKey(),similarity);
                }
                behavior=numerator/strength;
            }
            double multiplier=recentPurchases.containsKey(p.getId())?.25:1;
            Double score=behavior==null?null:behavior*multiplier;
            boolean personal=behavior!=null && behavior>0;
            String reason=personal?null:!activated?"REQUEST_FALLBACK":behavior==null?"MISSING_CANDIDATE_EMBEDDING":"NON_POSITIVE_SCORE";
            decisions.add(new Decision(p,profileMatch(p,profile),behavior,multiplier,score,personal,
                    reason,List.copyOf(exclusions),Map.copyOf(similarities),recentPurchases.get(p.getId())));
        }
        Comparator<Decision> profileOrder=Comparator.comparing((Decision d)->d.profileMatch().category()).reversed()
                .thenComparing(Comparator.comparing((Decision d)->d.profileMatch().budget()).reversed())
                .thenComparing(Comparator.comparing((Decision d)->d.profileMatch().gender()).reversed());
        Comparator<Decision> personalOrder=Comparator.comparing(Decision::score,Comparator.reverseOrder())
                .thenComparing(profileOrder).thenComparing(d->d.product().getId());
        Comparator<Decision> fallbackOrder=profileOrder
                .thenComparing(Comparator.comparingInt((Decision d)->Math.max(0,d.product().getSoldCount())).reversed())
                .thenComparing(d->d.product().getId());
        decisions.sort((a,b)-> {
            int excluded=Boolean.compare(!a.exclusions().isEmpty(),!b.exclusions().isEmpty());
            if (excluded!=0) return excluded;
            int source=Boolean.compare(!a.personalized(),!b.personalized());
            if (source!=0) return source;
            return (a.personalized()?personalOrder:fallbackOrder).compare(a,b);
        });
        boolean hasPersonal=decisions.stream().anyMatch(d->d.exclusions().isEmpty() && d.personalized());
        String fallbackReason=!index.isAvailable()?"MODA_UNAVAILABLE":raw.isEmpty()?"NO_BEHAVIOR":usable.isEmpty()?"NO_EMBEDDED_BEHAVIOR":
                !activated?"WEAK_BEHAVIOR":!hasPersonal?"NO_POSITIVE_SIMILARITY":null;
        return new Assessment(userId,now,profile,Map.copyOf(usable),Map.copyOf(raw),strength,rawStrength,
                index.isAvailable(),index.version(),index.failure(),hasPersonal?"PERSONALIZED":"FALLBACK",fallbackReason,
                Set.copyOf(inCart),Map.copyOf(recentPurchases),List.copyOf(signals),limit,List.copyOf(decisions));
    }

    private void collect(String id,String source,OffsetDateTime at,double base,double weight,String reason,String orderId,
                         Map<String,Product> catalog,Map<String,Double> raw,Map<String,Double> usable,List<Signal> signals,boolean explain) {
        if (reason==null && (!catalog.containsKey(id) || !(weight>0))) reason="INVALID_SOURCE";
        if (reason==null) {
            raw.merge(id,weight,Math::max);
            if (!index.isAvailable()) reason="MODA_UNAVAILABLE";
            else if (!index.contains(id)) reason="MISSING_SOURCE_EMBEDDING";
            else usable.merge(id,weight,Math::max);
        }
        if (explain) signals.add(new Signal(id,source,at,base,weight,reason,orderId));
    }
    private boolean within(OffsetDateTime at,OffsetDateTime now,int days) {
        return at!=null && !at.isBefore(now.minusDays(days)) && !at.isAfter(now);
    }
    private double decay(double base,OffsetDateTime at,OffsetDateTime now,double halfLife) {
        if (at==null || at.isAfter(now)) return 0;
        Duration elapsed=Duration.between(at,now);
        double days=Math.max(0,(elapsed.getSeconds()+elapsed.getNano()/1e9)/86400.0);
        return base*Math.pow(.5,days/halfLife);
    }
    private boolean inStock(Product p) {
        return p.getColors()!=null && p.getColors().stream().filter(Objects::nonNull).anyMatch(c->c.getSizes()!=null
                && c.getSizes().stream().filter(Objects::nonNull).anyMatch(s->s.isAvailable() && s.getInventory()>0));
    }
    private ProfileMatch profileMatch(Product p,UserProfileResponse profile) {
        if (profile==null) return new ProfileMatch(false,false,false);
        boolean category=profile.getPreferredCategories()!=null && profile.getPreferredCategories().stream()
                .filter(s->!normalize(s).isEmpty()).anyMatch(s->matchesPreference(p,s));
        boolean budget=(profile.getPriceMin()!=null || profile.getPriceMax()!=null) && p.getPrice()!=null
                && (profile.getPriceMin()==null || p.getPrice().compareTo(profile.getPriceMin())>=0)
                && (profile.getPriceMax()==null || p.getPrice().compareTo(profile.getPriceMax())<=0);
        boolean gender=!normalize(profile.getGender()).isEmpty() && genderMatches(p.getGender(),profile.getGender());
        return new ProfileMatch(category,budget,gender);
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

    record ProfileMatch(boolean category,boolean budget,boolean gender) {}
    record Signal(String productId,String source,OffsetDateTime at,double baseWeight,double decayedWeight,String reason,String orderId) {}
    record Decision(Product product,ProfileMatch profileMatch,Double behaviorScore,double purchaseMultiplier,Double score,
                    boolean personalized,String fallbackReason,List<String> exclusions,Map<String,Double> sourceSimilarities,String purchaseOrderId) {}
    record Assessment(UUID userId,OffsetDateTime at,UserProfileResponse profile,Map<String,Double> interests,
                      Map<String,Double> rawInterests,Double behaviorStrength,double rawStrength,boolean modaAvailable,
                      String indexVersion,String modaFailure,String mode,String fallbackReason,Set<String> inCart,
                      Map<String,String> recentlyPurchased,List<Signal> signals,int limit,List<Decision> decisions) {}
}
