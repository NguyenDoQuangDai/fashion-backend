package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatClarificationContractTest {
    final ObjectMapper json=new ObjectMapper();
    final ChatModelClient client=mock(ChatModelClient.class);
    final ChatCriteriaParser parser=new ChatCriteriaParser(client);
    ObjectNode state() {return json.valueToTree(ChatCriteriaParserTest.criteria());}
    ChatRequest request(String message) {var r=new ChatRequest();r.setMessage(message);return r;}
    void output(ObjectNode state) throws Exception {
        when(client.complete(anyString(),anyString(),any(),eq(512))).thenReturn(new ChatModelClient.Completion(json.writeValueAsString(state),null,null,1));
    }
    @Test void contradictoryFiltersAskEvenWhenModelForgotFlag() throws Exception {
        for (String mode:List.of("price","color")) {
            var n=state();
            if(mode.equals("price"))n.put("priceMin",400000); else n.putArray("colorsInclude").add("do");
            output(n);var parsed=parser.parse(request("Áo polo nam"));
            assertEquals("clarification",parsed.reasonCode());
            assertEquals("clarification",parsed.criteria().intent());assertTrue(parsed.criteria().needsClarification());
            assertNotNull(parsed.rawCriteria());assertFalse(parsed.rawCriteria().path("needsClarification").asBoolean());
        }
    }
    @Test void eitherClarificationSignalWinsAndDoesNotRequireGrounding() throws Exception {
        for(boolean flag:List.of(true,false)) {
            var n=state();n.put("needsClarification",flag);n.put("intent",flag?"product_search":"clarification");
            n.putNull("clarificationQuestion");output(n);
            var parsed=parser.parse(request("Tìm cả áo polo lẫn quần kaki"));
            assertEquals("clarification",parsed.criteria().intent());assertTrue(parsed.criteria().needsClarification());
            assertFalse(parsed.criteria().clarificationQuestion().isBlank());
        }
    }
    @Test void clarificationStillEnforcesEveryStructuralAndResourceLimit() {
        var valid=state();valid.put("needsClarification",true);
        var mutations=new ArrayList<ObjectNode>();
        var n=valid.deepCopy();n.put("quantity",11);mutations.add(n);
        n=valid.deepCopy();n.put("priceMax",-1);mutations.add(n);
        n=valid.deepCopy();n.put("priceMin",1000000001L);mutations.add(n);
        n=valid.deepCopy();n.put("quantity",1.5);mutations.add(n);
        n=valid.deepCopy();n.remove("gender");mutations.add(n);
        n=valid.deepCopy();n.put("extra",true);mutations.add(n);
        n=valid.deepCopy();n.put("intent","unknown");mutations.add(n);
        n=valid.deepCopy();n.put("clarificationQuestion","x".repeat(181));mutations.add(n);
        n=valid.deepCopy();n.put("size","x".repeat(11));mutations.add(n);
        n=valid.deepCopy();n.putArray("colorsInclude").add("x").add("x");mutations.add(n);
        n=valid.deepCopy();var arr=n.putArray("colorsInclude");for(int i=0;i<6;i++)arr.add("color"+i);mutations.add(n);
        n=valid.deepCopy();n.putArray("materialPreferences").add(2);mutations.add(n);
        for(var broken:mutations)assertEquals("schema_error",assertThrows(ChatCriteriaParser.CriteriaFailure.class,()->parser.validate(broken)).reasonCode());
        assertThrows(IllegalArgumentException.class,()->parser.parse(request("x".repeat(1201))));
        var r=request("Áo");r.setHistory(Collections.nCopies(7,new ChatRequest.Turn("user","Áo")));
        assertThrows(IllegalArgumentException.class,()->parser.parse(r));
        r.setHistory(Collections.nCopies(4,new ChatRequest.Turn("user","x".repeat(1200))));
        assertThrows(IllegalArgumentException.class,()->parser.parse(r));verifyNoInteractions(client);
    }
    @Test void failureReasonsStayDistinct() throws Exception {
        var r=request("Áo polo nam");
        when(client.complete(anyString(),anyString(),any(),eq(512))).thenThrow(new ChatModelClient.Unavailable("timeout"));
        assertEquals("model_timeout",assertThrows(ChatModelClient.Unavailable.class,()->parser.parse(r)).reasonCode());
        var ungrounded=state();ungrounded.putNull("priceMax");output(ungrounded);
        assertEquals("grounding_error",assertThrows(ChatModelClient.Unavailable.class,()->parser.parse(request("Quần"))).reasonCode());
        var n=state();for(String k:List.of("category","productType","gender","priceMax","size"))n.putNull(k);
        n.putArray("colorsExclude");output(n);
        assertEquals("validation_error",assertThrows(ChatModelClient.Unavailable.class,()->parser.parse(r)).reasonCode());
        when(client.complete(anyString(),anyString(),any(),eq(512))).thenReturn(new ChatModelClient.Completion("{",null,null,1));
        assertEquals("schema_error",assertThrows(ChatModelClient.Unavailable.class,()->parser.parse(r)).reasonCode());
    }
    @Test void duplicateFieldsAndTrailingJsonAreSchemaErrors() throws Exception {
        var n=state();n.put("needsClarification",true);
        String valid=json.writeValueAsString(n);
        for(String raw:List.of(valid+" {}",valid.replace("\"quantity\":5","\"quantity\":5,\"quantity\":1"))) {
            when(client.complete(anyString(),anyString(),any(),eq(512))).thenReturn(new ChatModelClient.Completion(raw,null,null,1));
            assertEquals("schema_error",assertThrows(ChatModelClient.Unavailable.class,()->parser.parse(request("Áo"))).reasonCode());
        }
    }
    @Test void apiContractUsesActualParserAndServiceAndNeverTouchesCatalog() throws Exception {
        var search=mock(ChatProductSearchService.class);var replies=mock(ChatReplyService.class);
        var service=new ChatService(parser,new ChatRuleFallback(),search,replies,"qwen",60);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.example.fashion_backend.controller.ChatController(service)).build();
        var n=state();n.put("priceMin",400000);output(n);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/chat")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("message","Áo polo ngân sách mâu thuẫn"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.suggestedProducts").isEmpty());
        clearInvocations(client);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/chat")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("message","x".repeat(1201)))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verifyNoInteractions(client,search,replies);
    }
    @Test void serviceNeverRetrievesOrGeneratesCardsForClarification() throws Exception {
        var search=mock(ChatProductSearchService.class);var replies=mock(ChatReplyService.class);
        var service=new ChatService(parser,new ChatRuleFallback(),search,replies,"qwen",60);
        for(String mode:List.of("price","color","multiple")) {
            var n=state();if(mode.equals("price"))n.put("priceMin",400000);
            if(mode.equals("color"))n.putArray("colorsInclude").add("do");
            if(mode.equals("multiple"))n.put("needsClarification",true);
            output(n);var response=service.chat(request("Tìm cả áo polo lẫn quần kaki"));
            assertTrue(response.getSuggestedProducts().isEmpty());assertTrue(response.getReply().contains("?"));
        }
        verifyNoInteractions(search,replies);
    }
    @Test void rejectedExtractionCannotFallThroughToRuleRetrieval() throws Exception {
        var search=mock(ChatProductSearchService.class);var replies=mock(ChatReplyService.class);
        var service=new ChatService(parser,new ChatRuleFallback(),search,replies,"qwen",60);
        var n=state();n.put("needsClarification",true);n.put("quantity",11);output(n);
        assertTrue(service.chat(request("Áo polo nam")).getSuggestedProducts().isEmpty());
        verifyNoInteractions(search,replies);
    }
}
