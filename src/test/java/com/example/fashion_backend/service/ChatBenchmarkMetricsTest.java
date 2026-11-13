package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatBenchmarkMetricsTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode state(){return json.valueToTree(ChatCriteriaParserTest.criteria());}
    @Test void comparesEquivalentNumbersAndIgnoresInclusiveOnlyForNullBounds() {
        assertTrue(ChatBenchmarkMetrics.same(json.getNodeFactory().numberNode(300000),json.getNodeFactory().numberNode(300000L)));
        assertTrue(ChatBenchmarkMetrics.same(json.getNodeFactory().numberNode(300000),json.getNodeFactory().numberNode(new java.math.BigDecimal("300000.00"))));
        var e=state();var a=e.deepCopy();a.put("priceMinInclusive",false);
        assertTrue(ChatBenchmarkMetrics.evaluate(e,a).path("hardConstraintsCorrect").asBoolean());
        assertFalse(new ChatPilotTest().same(e.get("priceMinInclusive"),a.get("priceMinInclusive")),"Legacy definition remains unchanged");
        a.put("priceMaxInclusive",true);assertFalse(ChatBenchmarkMetrics.evaluate(e,a).path("hardConstraintsCorrect").asBoolean());
    }
    @Test void separatesIntentHardSoftQuantityAndSort() {
        var e=state();var a=e.deepCopy();a.put("intent","product_refinement");
        var m=ChatBenchmarkMetrics.evaluate(e,a);assertFalse(m.path("intentCorrect").asBoolean());assertTrue(m.path("hardConstraintsCorrect").asBoolean());
        a.putArray("materialPreferences").add("cotton");a.put("quantity",2);a.put("sort","price_asc");
        m=ChatBenchmarkMetrics.evaluate(e,a);assertFalse(m.path("softPreferencesCorrect").asBoolean());assertFalse(m.path("quantityCorrect").asBoolean());assertFalse(m.path("sortCorrect").asBoolean());assertTrue(m.path("hardConstraintsCorrect").asBoolean());
    }
    @Test void clarificationAndErrorsCannotCountAsSuccessfulSearches() {
        var e=state();e.put("needsClarification",true);e.put("intent","clarification");var a=e.deepCopy();a.put("clarificationQuestion","Loại nào?");
        var m=ChatBenchmarkMetrics.evaluate(e,a);assertTrue(m.path("clarificationCorrect").asBoolean());assertTrue(m.path("hardConstraintsCorrect").isNull());
        a.put("intent","product_search");assertFalse(ChatBenchmarkMetrics.evaluate(e,a).path("clarificationCorrect").asBoolean());
        m=ChatBenchmarkMetrics.evaluate(state(),null);assertFalse(m.path("hardConstraintsCorrect").asBoolean());assertFalse(m.path("intentCorrect").asBoolean());
        var r=ChatBenchmarkMetrics.retrieval(e,json.createArrayNode(),json.createArrayNode(),false);assertTrue(r.path("routingCorrect").asBoolean());assertTrue(r.path("priceOrderCorrect").isNull());
    }
    @Test void retrievalChecksCountRoutingAndOrderWithoutClaimingRecall() {
        var e=state();e.put("quantity",1);e.put("sort","price_asc");var cards=json.createArrayNode();cards.addObject().put("price",200);cards.addObject().put("price",100);
        var m=ChatBenchmarkMetrics.retrieval(e,cards,json.createArrayNode(),true);assertFalse(m.path("quantityLimitRespected").asBoolean());assertFalse(m.path("priceOrderCorrect").asBoolean());assertTrue(m.path("routingCorrect").asBoolean());
    }
}
