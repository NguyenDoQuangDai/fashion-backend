package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatCriteria;
import com.example.fashion_backend.dto.chat.ChatRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatAliasCanonicalizationTest {
    private ChatRequest request(String message) {
        var request=new ChatRequest();request.setMessage(message);return request;
    }
    private ChatCriteria criteria(String category,String type,String gender) {
        return new ChatCriteria("product_search",category,type,gender,null,null,true,true,
            List.of(),List.of(),null,List.of(),null,5,"relevance",false,null);
    }
    @Test void canonicalizesOnlyUniqueExplicitProductPhrases() {
        for(String phrase:List.of("áo phông","ao phong")) {
            String message="Tìm "+phrase+" cho mùa hè";
            var evidence=ChatCriteriaGrounding.aliasEvidence(message);
            assertNotNull(evidence);
            assertEquals(phrase,message.substring(evidence.start(),evidence.end()));
            assertEquals("ao",evidence.category());assertEquals("thun",evidence.productType());
            var result=ChatCriteriaGrounding.validate(request(message),criteria("ao",null,null));
            assertEquals("thun",result.productType());assertFalse(result.needsClarification());
        }
        for(String phrase:List.of("quần thể thao","quan the thao")) {
            String message="Tìm "+phrase+" nam";
            var evidence=ChatCriteriaGrounding.aliasEvidence(message);
            assertNotNull(evidence);
            assertEquals(phrase,message.substring(evidence.start(),evidence.end()));
            var result=ChatCriteriaGrounding.validate(request(message),criteria("quan",null,"nam"));
            assertEquals("the thao",result.productType());assertEquals("nam",result.gender());
            assertFalse(result.needsClarification());
        }
    }
    @Test void fillsOnlyMissingCategoryWhenTypeAlreadyMatches() {
        var result=ChatCriteriaGrounding.validate(request("Tìm áo phông"),criteria(null,"thun",null));
        assertEquals("ao",result.category());assertEquals("thun",result.productType());
        assertEquals(criteria("ao","thun",null),result);
    }
    @Test void nearPhrasesMaterialsPurposeNegationAndCompetingTypesAreNotEvidence() {
        for(String message:List.of("Tìm áo phong cách đẹp","Áo vải thun thoáng mát",
                "Tìm quần để đi thể thao","Không lấy quần thể thao",
                "Tìm áo phông và quần thể thao","Tìm áo phông và áo polo",
                "Tìm quần thể thao và quần jeans")) {
            assertNull(ChatCriteriaGrounding.aliasEvidence(message),message);
        }
        var broad=criteria("ao",null,null);
        assertEquals(broad,ChatCriteriaGrounding.validate(request("Tìm áo phong cách đẹp"),broad));
        assertThrows(IllegalArgumentException.class,()->ChatCriteriaGrounding.validate(
            request("Tìm quần để đi thể thao"),criteria("quan",null,null)));
        assertThrows(IllegalArgumentException.class,()->ChatCriteriaGrounding.validate(
            request("Không lấy quần thể thao"),criteria("quan",null,null)));
        assertThrows(IllegalArgumentException.class,()->ChatCriteriaGrounding.validate(
            request("Tìm áo phông và áo polo"),criteria("ao",null,null)));
        assertEquals("thun",ChatCriteriaGrounding.validate(request("Tìm áo phông để chơi thể thao"),
            criteria("ao",null,null)).productType());
    }
    @Test void doesNotOverwriteConflictingConcreteValueOrInheritAssistantHistory() {
        var request=request("Chỉ màu đen");
        request.setHistory(List.of(new ChatRequest.Turn("assistant","Bạn muốn áo phông không?")));
        assertNull(ChatCriteriaGrounding.aliasEvidence(request.getMessage()));
        var old=criteria("ao",null,null);
        assertEquals(old,ChatCriteriaGrounding.validate(request,old));
        var wrongCategory=criteria("quan",null,null);
        assertEquals(wrongCategory,ChatCriteriaGrounding.validate(request("Tìm áo phông"),wrongCategory));
    }
}
