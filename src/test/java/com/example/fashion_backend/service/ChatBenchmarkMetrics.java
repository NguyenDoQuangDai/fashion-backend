package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Versioned diagnostic metrics. Does not replace legacy exactConstraints or intentCorrect. */
final class ChatBenchmarkMetrics {
    static final ObjectMapper JSON = new ObjectMapper();
    static boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null) return Objects.equals(a,b);
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isArray() && b.isArray()) {
            var x = new TreeSet<String>(); var y = new TreeSet<String>();
            a.forEach(v -> x.add(v.asText())); b.forEach(v -> y.add(v.asText())); return x.equals(y);
        }
        return a.equals(b);
    }
    static ObjectNode evaluate(JsonNode expected, JsonNode actual) {
        var out = JSON.createObjectNode(); out.put("version","2a-v1");
        boolean available = actual != null && actual.isObject();
        out.put("intentCorrect", available && same(expected.get("intent"),actual.get("intent")));
        boolean clarify = expected.path("needsClarification").asBoolean();
        out.put("clarificationCorrect", available && same(expected.get("needsClarification"),actual.get("needsClarification"))
            && actual.path("needsClarification").asBoolean()==actual.path("intent").asText().equals("clarification")
            && (!actual.path("needsClarification").asBoolean() || (actual.path("intent").asText().equals("clarification")
                && !actual.path("clarificationQuestion").asText("").isBlank())));
        for (var group : Map.of(
                "hardConstraintsCorrect",List.of("category","productType","gender","priceMin","priceMax","priceMinInclusive","priceMaxInclusive","colorsInclude","colorsExclude","size"),
                "softPreferencesCorrect",List.of("materialPreferences","occasion"),
                "quantityCorrect",List.of("quantity"),"sortCorrect",List.of("sort")).entrySet()) {
            // Clarification gold describes a question, not executable filters. Never count N/A as a pass.
            if (clarify) { out.putNull(group.getKey()); continue; }
            boolean correct = available;
            for (String key : group.getValue()) {
                if (key.endsWith("Inclusive")) {
                    String bound = key.replace("Inclusive", "");
                    if (expected.path(bound).isNull() && available && actual.path(bound).isNull()) continue;
                }
                correct &= available && same(expected.get(key),actual.get(key));
            }
            out.put(group.getKey(),correct);
        }
        return out;
    }
    static ObjectNode retrieval(JsonNode expected, JsonNode products, JsonNode violations, boolean executed) {
        var result=JSON.createObjectNode(); result.put("executed",executed); result.put("returnedCount",products.size());
        result.set("legacyHardViolations",violations);
        if (!executed || products.isEmpty()) result.putNull("returnedCardsPassLegacyChecks");
        else result.put("returnedCardsPassLegacyChecks",violations.isEmpty());
        boolean shouldSearch=!expected.path("needsClarification").asBoolean() && expected.path("intent").asText().startsWith("product_");
        result.put("routingCorrect",executed==shouldSearch);
        if (expected.path("needsClarification").asBoolean()) result.put("noCardsWhenClarifying",products.isEmpty());
        else result.putNull("noCardsWhenClarifying");
        if (executed && !products.isEmpty()) result.put("quantityLimitRespected",products.size()<=expected.path("quantity").asInt(5));
        else result.putNull("quantityLimitRespected");
        if (!shouldSearch) {result.putNull("priceOrderCorrect");return result;}
        boolean ordered=true; String sort=expected.path("sort").asText();
        for(int i=1;i<products.size();i++) {
            int cmp=products.get(i-1).path("price").decimalValue().compareTo(products.get(i).path("price").decimalValue());
            if ((sort.equals("price_asc")&&cmp>0)||(sort.equals("price_desc")&&cmp<0))ordered=false;
        }
        if(sort.equals("relevance") || products.size()<2)result.putNull("priceOrderCorrect");
        else result.put("priceOrderCorrect",ordered);
        // No relevance/recall claim without an independent list of eligible products.
        return result;
    }
}
