package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.user.UserProfileResponse;
import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.model.*;
import com.example.fashion_backend.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {
    final ProductService products=mock(ProductService.class);
    final UserProfileService profiles=mock(UserProfileService.class);
    final CartService carts=mock(CartService.class);
    final OrderRepository orders=mock(OrderRepository.class);
    final UserProductViewRepository views=mock(UserProductViewRepository.class);
    final ProductSimilarityIndex index=mock(ProductSimilarityIndex.class);
    final UUID user=UUID.randomUUID();
    final OffsetDateTime now=OffsetDateTime.parse("2026-10-23T00:00:00Z");
    final RecommendationService service=new RecommendationService(products,profiles,carts,orders,views,index,Clock.fixed(now.toInstant(),ZoneOffset.UTC));

    @BeforeEach void setup() {
        when(profiles.getProfile(user)).thenReturn(new UserProfileResponse());
        when(carts.getCartItems(user,null)).thenReturn(List.of());
        when(orders.findByUserId(user)).thenReturn(List.of());
        when(views.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(eq(user),any())).thenReturn(List.of());
        when(index.isAvailable()).thenReturn(true); when(index.contains(anyString())).thenReturn(true);
        when(index.similarity(anyString(),anyString())).thenAnswer(i->i.getArgument(0).equals(i.getArgument(1))?1.0:.2);
        when(products.getAllProducts()).thenReturn(List.of(product("source","polo",0),product("a","polo",0),product("b","jeans",1000)));
    }
    Product product(String id,String type,int sold) {
        Product p=new Product(); p.setId(id); p.setCategorySlug(type); p.setType(type); p.setTags(List.of(type));
        p.setGender("nam"); p.setPrice(BigDecimal.valueOf(200000)); p.setSoldCount(sold);
        ProductSizeVariant s=new ProductSizeVariant(); s.setAvailable(true); s.setInventory(5);
        ProductColorVariant c=new ProductColorVariant(); c.setSizes(List.of(s)); p.setColors(List.of(c)); return p;
    }
    void cart(String... ids) { when(carts.getCartItems(user,null)).thenReturn(Arrays.stream(ids).map(id->new CartItem(id,10)).toList()); }
    void viewed(String id,OffsetDateTime at) {
        var v=new UserProductViewEntity(); v.setProductId(id); v.setLastViewedAt(at);
        when(views.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(eq(user),any())).thenReturn(List.of(v));
    }
    OrderEntity order(String id,OffsetDateTime at) {
        var o=new OrderEntity(); o.setId(UUID.randomUUID()); o.setStatus("completed"); o.setPaymentStatus(PaymentStatus.PAID); o.setCreatedAt(at);
        var i=new OrderItemEntity(); i.setProductId(id); i.setQuantity(5); o.setItems(List.of(i)); return o;
    }
    RecommendationService.Assessment report() { return service.assess(12,null,null,null,null,List.of(),user); }
    RecommendationService.Decision decision(String id) { return report().decisions().stream().filter(d->d.product().getId().equals(id)).findFirst().orElseThrow(); }
    List<String> ranked() { return service.recommend(12,null,null,null,null,List.of(),user).stream().map(Product::getId).toList(); }
    void sim(String source,String target,double value) { when(index.similarity(source,target)).thenReturn(value); }
    void profile(String... types) { var p=new UserProfileResponse(); p.setPreferredCategories(List.of(types)); when(profiles.getProfile(user)).thenReturn(p); }

    @Test void newUserAndWeakBehaviorFallbackUseProfileThenSoldCount() {
        profile("polo"); assertEquals(List.of("a","source","b"),ranked());
        viewed("a",now); assertEquals("WEAK_BEHAVIOR",report().fallbackReason()); assertEquals(1,report().behaviorStrength());
        assertEquals(List.of("a","source","b"),ranked());
    }
    @Test void gateIsExactAfterDecayAndDoesNotRound() {
        var values=new ArrayList<UserProductViewEntity>();
        for(String id:List.of("source","a","b")) { var v=new UserProductViewEntity();v.setProductId(id);v.setLastViewedAt(now);values.add(v); }
        when(views.findTop50ByUserIdAndLastViewedAtAfterOrderByLastViewedAtDescIdDesc(eq(user),any())).thenReturn(values);
        assertEquals(3,report().behaviorStrength()); assertEquals("PERSONALIZED",report().mode());
        values.get(0).setLastViewedAt(now.minusSeconds(1));
        assertTrue(report().behaviorStrength()<3); assertEquals("WEAK_BEHAVIOR",report().fallbackReason());
    }
    @Test void aCartIsFiveRegardlessOfQuantityAndDuplicates() {
        cart("source","source"); viewed("source",now);
        assertEquals(5,report().behaviorStrength()); assertEquals(5,report().interests().get("source"));
        assertFalse(ranked().contains("source"));
        when(orders.findByUserId(user)).thenReturn(List.of(order("source",now)));
        assertEquals(10,report().behaviorStrength());
    }
    @Test void decayPreservesRealWeightsInScoreNotTheGateValue() {
        cart("source"); when(orders.findByUserId(user)).thenReturn(List.of(order("a",now.minusDays(120))));
        sim("source","b",.8); sim("a","b",-.2);
        assertEquals(7.5,report().behaviorStrength()); assertEquals((5*.8+2.5*-.2)/7.5,decision("b").behaviorScore(),1e-12);
        assertEquals(7.5,report().behaviorStrength());
    }
    @Test void purchaseWindowInclusiveAndOldOrdersCannotAccumulateForever() {
        assertEquals(360,RecommendationService.PURCHASE_HISTORY_DAYS);
        var old=new ArrayList<OrderEntity>();
        for(String id:List.of("source","a","b")) old.add(order(id,now.minusDays(RecommendationService.PURCHASE_HISTORY_DAYS)));
        when(orders.findByUserId(user)).thenReturn(old);
        assertEquals(3*10*Math.pow(.5,RecommendationService.PURCHASE_HISTORY_DAYS/60.0),report().behaviorStrength(),1e-12);
        old.forEach(o->o.setCreatedAt(now.minusDays(RecommendationService.PURCHASE_HISTORY_DAYS).minusSeconds(1)));
        assertEquals(0,report().behaviorStrength()); assertEquals("NO_BEHAVIOR",report().fallbackReason());
        cart("source"); assertEquals(5,report().behaviorStrength());
    }
    @Test void purchaseAgeUsesCreatedAtNotUpdatedOrPaidTime() {
        var o=order("source",now.minusDays(RecommendationService.PURCHASE_HISTORY_DAYS+1)); o.setUpdatedAt(now); o.setPaidAt(now);
        when(orders.findByUserId(user)).thenReturn(List.of(o)); assertEquals(0,report().behaviorStrength());
        o.setCreatedAt(now.minusDays(181)); assertTrue(report().behaviorStrength()>0);
        assertEquals(1,decision("source").purchaseMultiplier());
        o.setCreatedAt(now.minusDays(40)); assertTrue(report().behaviorStrength()>=3); assertEquals(1,decision("source").purchaseMultiplier());
    }
    @Test void invalidOrdersAndTimestampsDoNotCount() {
        var future=order("source",now.plusSeconds(1)); var missing=order("source",null);
        var cancelled=order("a",now); cancelled.setStatus("cancelled");
        var failed=order("b",now); failed.setPaymentStatus(PaymentStatus.FAILED);
        when(orders.findByUserId(user)).thenReturn(List.of(future,missing,cancelled,failed));
        assertEquals(0,report().behaviorStrength()); assertTrue(report().recentlyPurchased().isEmpty());
    }
    @Test void purchaseMultiplierOnlyExactProductAndAppliedOnceAtThirtyDayBoundary() {
        var o=order("source",now.minusDays(30)); when(orders.findByUserId(user)).thenReturn(List.of(o,o));
        sim("source","a",1); assertEquals(.25,decision("source").score()); assertEquals(1,decision("a").score());
        assertEquals(.25,decision("source").purchaseMultiplier()); assertEquals(1,decision("a").purchaseMultiplier());
        double strength=report().behaviorStrength(); assertEquals(10*Math.pow(.5,.5),strength,1e-12);
        o.setCreatedAt(now.minusDays(30).minusSeconds(1)); assertEquals(1,decision("source").purchaseMultiplier());
    }
    @Test void signedCosineIsNotClampedAndNonpositiveScoresOnlyUseFallbackFill() {
        cart("source","a"); sim("source","b",.8); sim("a","b",-.2);
        assertEquals(.3,decision("b").behaviorScore(),1e-12);
        sim("source","b",-.4); sim("a","b",-.2);
        assertEquals(-.3,decision("b").behaviorScore(),1e-12); assertFalse(decision("b").personalized());
        assertEquals("NO_POSITIVE_SIMILARITY",report().fallbackReason());
    }
    @Test void profileOnlyBreaksExactTiesAndPopularityNeverChangesPersonalOrder() {
        cart("source"); profile("jeans"); sim("source","a",.850001); sim("source","b",.85);
        assertEquals(List.of("a","b"),ranked());
        sim("source","a",.85); assertEquals(List.of("b","a"),ranked());
        profile(); assertEquals(List.of("a","b"),ranked());
        var a=products.getAllProducts().get(1); a.setSoldCount(999999); assertEquals(List.of("a","b"),ranked());
    }
    @Test void exactTieUsesCategoryThenBudgetThenGenderThenId() {
        cart("source"); profile("jeans"); assertEquals("b",ranked().get(0));
        var p=new UserProfileResponse(); p.setPriceMax(BigDecimal.valueOf(150000)); p.setGender("nu");
        when(profiles.getProfile(user)).thenReturn(p);
        var a=products.getAllProducts().get(1); var b=products.getAllProducts().get(2);
        a.setPrice(BigDecimal.valueOf(100000)); assertEquals("a",ranked().get(0));
        b.setPrice(BigDecimal.valueOf(100000)); b.setGender("nu"); assertEquals("b",ranked().get(0));
        a.setGender("unisex"); assertEquals("a",ranked().get(0));
    }
    @Test void missingEmbeddingCannotHelpReachGateAndUnavailableIsNotZeroStrength() {
        cart("source"); viewed("a",now); when(index.contains("source")).thenReturn(false);
        assertEquals(6,report().rawStrength()); assertEquals(1,report().behaviorStrength()); assertEquals("WEAK_BEHAVIOR",report().fallbackReason());
        when(index.isAvailable()).thenReturn(false); assertNull(report().behaviorStrength()); assertEquals("MODA_UNAVAILABLE",report().fallbackReason());
    }
    @Test void missingCandidateEmbeddingIsFallbackFillAndCannotJumpPersonalized() {
        cart("source"); when(index.contains("b")).thenReturn(false); profile("jeans");
        assertEquals(List.of("a","b"),ranked()); assertNull(decision("b").score()); assertFalse(decision("b").personalized());
    }
    @Test void stockAndExplicitQueryAreHardButProfileIsSoft() {
        profile("jeans"); var b=products.getAllProducts().get(2); b.setColors(List.of());
        assertFalse(ranked().contains("b")); assertTrue(ranked().contains("a"));
        assertTrue(service.recommend(12,null,null,BigDecimal.valueOf(300000),null,List.of(),user).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.recommend(12,null,null,BigDecimal.TEN,BigDecimal.ONE,List.of(),user));
    }
    @Test void sourceMayBeOutOfStockAndStillDescribeInterest() {
        cart("source"); products.getAllProducts().get(0).setColors(List.of());
        assertEquals(5,report().behaviorStrength()); assertEquals("PERSONALIZED",report().mode()); assertFalse(ranked().contains("source"));
    }
    @Test void assessmentAndApiChooseIdenticalResultsAndDeduplicateCatalog() {
        cart("source"); var list=new ArrayList<>(products.getAllProducts()); list.add(list.get(1)); when(products.getAllProducts()).thenReturn(list);
        assertEquals(ranked(),report().decisions().stream().filter(d->d.exclusions().isEmpty()).limit(report().limit()).map(d->d.product().getId()).toList());
        assertEquals(2,ranked().size());
    }
    @Test void guestDoesNotReadAnyPrivateHistory() {
        var result=service.recommend(12,null,null,null,null,List.of(),null);
        assertEquals("b",result.get(0).getId()); verifyNoInteractions(profiles,carts,orders,views);
    }
    @Test void zeroAndNegativeSoldCountAreStableFallbackTies() {
        var a=product("a","polo",-5); var b=product("b","jeans",0); when(products.getAllProducts()).thenReturn(List.of(b,a));
        assertEquals(List.of("a","b"),ranked());
    }
    @Test void strengthTwoPointNineNineNineDoesNotPassGate() {
        double seconds=60*86400.0*Math.log(10/2.999)/Math.log(2);
        when(orders.findByUserId(user)).thenReturn(List.of(order("source",now.minusNanos((long)(seconds*1e9)))));
        assertEquals(2.999,report().behaviorStrength(),1e-10);
        assertEquals("WEAK_BEHAVIOR",report().fallbackReason());
    }
    @Test void canonicalDetailedTypesSupportGenderUnisexAndLegacyGenericPreferences() {
        var male=product("male","Áo Polo",0); var female=product("female","Áo Polo",0); female.setGender("nu");
        var unisex=product("unisex","Áo Polo",0); unisex.setGender("unisex");
        when(products.getAllProducts()).thenReturn(List.of(female,male,unisex));
        profile("Áo Polo Nam"); assertEquals(List.of("male","unisex","female"),ranked());
        profile("Áo Polo"); assertTrue(report().decisions().stream().allMatch(d->d.profileMatch().category()));
        profile("Áo"); var sport=product("sport","Quần Short Thể Thao",999); when(products.getAllProducts()).thenReturn(List.of(sport,male));
        assertEquals(List.of("male","sport"),ranked());
    }
}
