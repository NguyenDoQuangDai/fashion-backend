package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatPricePolicyTest {
    @Test void explicitMoneyCannotBeLoosenedByModelAndIncidentalNumbersAreIgnored() {
        var r=new ChatRequest();r.setMessage("Nam 25 tuổi 70kg cao 175cm tìm áo dưới 500k");
        var c=ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria());
        assertNull(c.priceMin());assertEquals(500000,c.priceMax());assertFalse(c.priceMaxInclusive());
        r.setMessage("Áo khoảng 1,5 triệu");c=ChatPricePolicy.apply(r,c);
        assertEquals(1450000,c.priceMin());assertEquals(1550000,c.priceMax());
        r.setMessage("Áo giá 1.000.000đ");c=ChatPricePolicy.apply(r,c);
        assertEquals(1000000,c.priceMin());assertEquals(1000000,c.priceMax());
        r.setMessage("Áo từ 200k đến 400k");c=ChatPricePolicy.apply(r,c);
        assertEquals(200000,c.priceMin());assertEquals(400000,c.priceMax());
        r.setMessage("Áo giá tốt cho người 70kg");
        assertThrows(IllegalArgumentException.class,()->ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria()));
    }
    @Test void invalidAndMultiplePriceExpressionsAskAgainRatherThanGuess() {
        var r=new ChatRequest();r.setMessage("Áo từ 400k đến 200k");
        assertThrows(IllegalArgumentException.class,()->ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria()));
        r.setMessage("Áo dưới 500k hoặc dưới 200k");
        assertThrows(IllegalArgumentException.class,()->ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria()));
    }
    @Test void aCompleteSearchStateCanGroundItsBudgetInUserHistory() {
        var r=new ChatRequest();r.setMessage("Chỉ màu đen");
        r.setHistory(java.util.List.of(new ChatRequest.Turn("user","Áo polo nam dưới 300k")));
        var c=ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria());
        assertEquals(300000,c.priceMax());assertFalse(c.priceMaxInclusive());
        r.setHistory(java.util.List.of(new ChatRequest.Turn("assistant","Giá dưới 300k")));
        assertThrows(IllegalArgumentException.class,()->ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria()));
        r.setMessage("Dưới 400k");r.setHistory(java.util.List.of(new ChatRequest.Turn("user","Áo từ 500k đến 200k")));
        assertEquals(400000,ChatPricePolicy.apply(r,ChatCriteriaParserTest.criteria()).priceMax());
    }
}
