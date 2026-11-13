package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.example.fashion_backend.model.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatProductSearchServiceTest {
    final ChatProductSearchService search = new ChatProductSearchService(null);
    final ObjectMapper json = new ObjectMapper();
    static Product product() {
        var p = new Product(); p.setId("p1"); p.setSlug("polo"); p.setTitle("Áo Polo nam"); p.setCategoryName("Áo Polo");
        p.setGender("nam"); p.setPrice(BigDecimal.valueOf(300000));
        p.setColors(List.of(color("Đen", size("M", 0, 250000), size("L", 5, 300000)),
                color("Đỏ", size("M", 4, 250000)))); return p;
    }
    static ProductSizeVariant size(String name, int stock, Integer price) {
        var s = new ProductSizeVariant(); s.setSize(name); s.setInventory(stock); s.setAvailable(stock > 0);
        s.setPrice(price == null ? null : BigDecimal.valueOf(price)); return s;
    }
    static ProductColorVariant color(String name, ProductSizeVariant... sizes) {
        var c = new ProductColorVariant(); c.setColorName(name); c.setCoverImage("/" + name + ".png"); c.setSizes(List.of(sizes)); return c;
    }
    ChatCriteria criteria(String overrides) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(ChatCriteriaParserTest.criteria());
        node.setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(overrides));
        return json.treeToValue(node, ChatCriteria.class);
    }
    @Test void sizeAndColorMustBeInTheSameInStockVariant() throws Exception {
        var c = criteria("{\"colorsInclude\":[\"den\"],\"size\":\"M\",\"priceMax\":500000}");
        assertTrue(search.search(c, List.of(product())).isEmpty());
        c = criteria("{\"colorsInclude\":[\"den\"],\"size\":\"L\",\"priceMax\":300000,\"priceMaxInclusive\":true}");
        var result = search.search(c, List.of(product()));
        assertEquals(1, result.size()); assertEquals("Đen", result.get(0).product().getMatchedColor());
        assertEquals(new BigDecimal("300000"), result.get(0).product().getPrice());
        assertEquals("/Đen.png", result.get(0).product().getCoverImage());
    }
    @Test void priceBoundariesNullPricesAndInventoryNeverRelax() throws Exception {
        var p = product();
        assertTrue(search.search(criteria("{\"size\":\"L\",\"priceMax\":300000}"), List.of(p)).isEmpty());
        assertEquals(1, search.search(criteria("{\"size\":\"L\",\"priceMin\":300000,\"priceMax\":300000,\"priceMaxInclusive\":true}"), List.of(p)).size());
        p.setPrice(null); p.setColors(List.of(color("Đen", size("M", 2, null))));
        assertTrue(search.search(criteria("{}"), List.of(p)).isEmpty());
        p.setPrice(BigDecimal.TEN); p.setColors(List.of(color("Đen", size("M", 0, 1))));
        assertTrue(search.search(criteria("{}"), List.of(p)).isEmpty());
    }
    @Test void categoryUsesWholeWordsAndExcludedMixedColorIsRejected() throws Exception {
        var p = product(); p.setTitle("Quần thể thao nam"); p.setCategoryName("Quần thể thao nam");
        assertTrue(search.search(criteria("{\"productType\":null,\"size\":null}"), List.of(p)).isEmpty());
        p.setColors(List.of(color("Trắng/Đen", size("M", 4, 200000))));
        assertTrue(search.search(criteria("{\"category\":\"quan\",\"productType\":null,\"colorsExclude\":[\"trang\"]}"), List.of(p)).isEmpty());
    }
    @Test void unknownSoftMetadataDoesNotBecomeAFactualClaim() throws Exception {
        var result = search.search(criteria("{\"size\":\"L\",\"priceMax\":null,\"materialPreferences\":[\"lua\"],\"occasion\":\"phong_van\"}"), List.of(product()));
        assertEquals(1, result.size()); assertFalse(result.get(0).evidence().contains("material"));
        assertFalse(result.get(0).evidence().contains("occasion"));
    }
}
