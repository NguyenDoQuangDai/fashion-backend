package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    @Test void unavailableParserNeverIgnoresNegativeOrBudgetConstraints() {
        var parser = mock(ChatCriteriaParser.class); var search = mock(ChatProductSearchService.class);
        when(parser.parse(any())).thenThrow(new ChatModelClient.Unavailable("timeout"));
        var service = new ChatService(parser, new ChatRuleFallback(), search, mock(ChatReplyService.class), "qwen", 60);
        var request = new ChatRequest(); request.setMessage("Áo không đỏ dưới 300k");
        var response = service.chat(request); assertTrue(response.getSuggestedProducts().isEmpty());
        assertTrue(response.getReply().contains("chưa phân tích")); verifyNoInteractions(search);
    }
    @Test void catalogFailureHasNoFabricatedProducts() {
        var parser = mock(ChatCriteriaParser.class); var search = mock(ChatProductSearchService.class);
        when(parser.parse(any())).thenReturn(new ChatCriteriaParser.Parsed(ChatCriteriaParserTest.criteria(), null));
        when(search.search(any())).thenThrow(new IllegalStateException("db"));
        var service = new ChatService(parser, new ChatRuleFallback(), search, mock(ChatReplyService.class), "qwen", 60);
        var request = new ChatRequest(); request.setMessage("Áo polo nam");
        assertTrue(service.chat(request).getReply().contains("chưa tra cứu"));
    }
    @Test void generationFailureAndInventedReasonBothUseGroundedTemplate() {
        var client = mock(ChatModelClient.class); var search = new ChatProductSearchService(null);
        var criteria = new ObjectMapper().valueToTree(ChatCriteriaParserTest.criteria());
        ((com.fasterxml.jackson.databind.node.ObjectNode)criteria).putNull("size");
        ((com.fasterxml.jackson.databind.node.ObjectNode)criteria).putNull("priceMax");
        var c = new ObjectMapper().convertValue(criteria, ChatCriteria.class);
        var matches = search.search(c, List.of(ChatProductSearchServiceTest.product()));
        when(client.complete(anyString(),anyString(),any(),eq(256),anyLong())).thenThrow(new ChatModelClient.Unavailable("timeout"));
        var replies = new ChatReplyService(client,true);
        String fallback = replies.reply(c,matches,1000);
        assertTrue(fallback.contains("300.000")); assertFalse(fallback.contains("cotton"));
        when(client.complete(anyString(),anyString(),any(),eq(256),anyLong())).thenReturn(
                new ChatModelClient.Completion("{\"reasons\":[{\"id\":\"fake\",\"reason\":\"material\"}]}",null,null,1));
        assertEquals(fallback,replies.reply(c,matches,1000));
    }
    @Test void httpClientUsesSchemaNonThinkingAndRejectsTruncationAndErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json = new ObjectMapper(); var finish = new String[]{"stop"}; var status = new int[]{200};
        server.createContext("/v1/chat/completions", exchange -> {
            var request=json.readTree(exchange.getRequestBody());
            assertFalse(request.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            assertEquals("json_schema",request.path("response_format").path("type").asText());
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish[0],"message",Map.of("content","{}")))));
            exchange.sendResponseHeaders(status[0],body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var client = new QwenChatClient("http://127.0.0.1:"+server.getAddress().getPort(),"test",2,42);
            assertEquals("{}",client.complete("system","input",json.createObjectNode(),512).content());
            finish[0]="length"; assertEquals("truncated",assertThrows(ChatModelClient.Unavailable.class,()->client.complete("s","i",json.createObjectNode(),512)).getMessage());
            status[0]=503; assertThrows(ChatModelClient.Unavailable.class,()->client.complete("s","i",json.createObjectNode(),512));
            assertThrows(ChatModelClient.Unavailable.class,()->client.complete("s","i",json.createObjectNode(),256,0));
        } finally { server.stop(0); }
    }
    @Test void legacyBaselineDocumentsPriceAndSubstringFailures() throws Exception {
        var legacy = new LegacyChatBaseline(mock(ProductService.class),new ObjectMapper(),"", "unused");
        var price=LegacyChatBaseline.class.getDeclaredMethod("extractPrice",String.class); price.setAccessible(true);
        assertEquals(new java.math.BigDecimal("25000"),price.invoke(legacy,"nam 25 tuoi 70kg tim ao duoi 500k"));
        var category=LegacyChatBaseline.class.getDeclaredMethod("extractCategoryType",String.class); category.setAccessible(true);
        assertEquals("ao",category.invoke(legacy,"quan the thao nam"));
        var range=LegacyChatBaseline.class.getDeclaredMethod("buildPriceRange",java.math.BigDecimal.class); range.setAccessible(true);
        var values=(java.math.BigDecimal[])range.invoke(legacy,new java.math.BigDecimal("300000"));
        assertEquals(new java.math.BigDecimal("350000"),values[1]);
    }
    @Test void slowEndpointTimesOutAndConcurrentInferenceIsRejected() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var entered=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{
            entered.countDown();
            try {Thread.sleep(1400);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var client=new QwenChatClient("http://127.0.0.1:"+server.getAddress().getPort(),"test",1,42);
            var schema=new ObjectMapper().createObjectNode();
            var first=executor.submit(()->assertThrows(ChatModelClient.Unavailable.class,()->client.complete("s","i",schema,32)));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("busy",assertThrows(ChatModelClient.Unavailable.class,()->client.complete("s","i",schema,32)).getMessage());
            assertEquals("timeout",first.get(3,java.util.concurrent.TimeUnit.SECONDS).getMessage());
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
