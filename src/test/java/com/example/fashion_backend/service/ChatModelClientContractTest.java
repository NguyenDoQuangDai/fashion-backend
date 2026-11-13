package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatCriteria;
import com.example.fashion_backend.dto.chat.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatModelClientContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void qwenWireAndCompletionStayTheSameThroughTheCommonContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var sent = new AtomicReference<JsonNode>();
        var usage = json.readTree("{\"prompt_tokens\":12,\"completion_tokens\":4}");
        var timings = json.readTree("{\"predicted_per_second\":25.0}");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                sent.set(json.readTree(exchange.getRequestBody()));
                var body = json.createObjectNode();
                var choice = body.putArray("choices").addObject();
                choice.put("finish_reason", "stop");
                choice.putObject("message").put("content", "{\"ok\":true}");
                body.set("usage", usage);
                body.set("timings", timings);
                byte[] response = json.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ChatModelClient client = new QwenChatClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "fashion-qwen3-4b", 2, 42);
            JsonNode schema = json.readTree("{\"type\":\"object\",\"properties\":{}}");
            var result = client.complete("system", "input", schema, 512);
            assertEquals("{\"ok\":true}", result.content());
            assertEquals(usage, result.usage());
            assertEquals(timings, result.timings());
            assertTrue(result.elapsedMs() >= 0);

            var request = sent.get();
            assertEquals("fashion-qwen3-4b", request.path("model").asText());
            assertEquals("system", request.path("messages").get(0).path("role").asText());
            assertEquals("system", request.path("messages").get(0).path("content").asText());
            assertEquals("user", request.path("messages").get(1).path("role").asText());
            assertEquals("input", request.path("messages").get(1).path("content").asText());
            assertEquals("json_schema", request.path("response_format").path("type").asText());
            assertTrue(request.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals(schema, request.path("response_format").path("json_schema").path("schema"));
            assertFalse(request.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            assertEquals(0.7, request.path("temperature").asDouble());
            assertEquals(0.8, request.path("top_p").asDouble());
            assertEquals(20, request.path("top_k").asInt());
            assertEquals(42, request.path("seed").asInt());
            assertEquals(512, request.path("max_tokens").asInt());
            assertFalse(request.path("stream").asBoolean(true));
        } finally { server.stop(0); }
    }

    @Test void parserAndRulesUseTheCommonClientWithoutChangingGroundingOrDefaultMode() throws Exception {
        ChatModelClient client = mock(ChatModelClient.class);
        var parser = new ChatCriteriaParser(client);
        var criteria = new ChatCriteria("product_search", "ao", "thun", "nam", null, null,
            true, true, List.of(), List.of(), null, List.of(), null, 5, "relevance", false, null);
        when(client.complete(anyString(), anyString(), any(), eq(512)))
            .thenReturn(new ChatModelClient.Completion(json.writeValueAsString(criteria), null, null, 1));
        var request = new ChatRequest();
        request.setMessage("Tìm áo phông nam");
        var parsed = parser.parse(request);
        assertEquals(criteria, parsed.criteria());
        assertEquals("ok", parsed.reasonCode());
        assertEquals(json.valueToTree(criteria), parsed.rawCriteria());
        verify(client, times(1)).complete(anyString(), anyString(), any(), eq(512));

        clearInvocations(client);
        var service = new ChatService(parser, new ChatRuleFallback(), mock(ChatProductSearchService.class),
            mock(ChatReplyService.class), "rules", 60);
        request.setMessage("Xin chào");
        assertTrue(service.chat(request).getSuggestedProducts().isEmpty());
        verifyNoInteractions(client);
    }

    @Test void defaultsAndRelativeDotEnvImportRemainConfigured() throws Exception {
        var properties = new java.util.Properties();
        try (var stream = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(stream);
            properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        assertEquals("optional:file:.env[.properties]", properties.getProperty("spring.config.import"));
        assertEquals("${CHAT_MODE:rules}", properties.getProperty("app.chat.mode"));
        assertEquals("${CHAT_GENERATE_REPLY:false}", properties.getProperty("app.chat.generate-reply"));
        assertEquals("${APP_GEMINI_API_KEY:}", properties.getProperty("app.chat.gemini.api-key"));
        assertEquals("${APP_GEMINI_MODEL:gemini-3.8-flash}", properties.getProperty("app.chat.gemini.model"));
        var environment = new org.springframework.mock.env.MockEnvironment()
            .withProperty("APP_GEMINI_API_KEY", "synthetic-test-key")
            .withProperty("APP_GEMINI_MODEL", "gemini-3.8-flash");
        assertEquals("synthetic-test-key",
            environment.resolvePlaceholders(properties.getProperty("app.chat.gemini.api-key")));
        assertEquals("gemini-3.8-flash",
            environment.resolvePlaceholders(properties.getProperty("app.chat.gemini.model")));
    }

    @Test void localDotEnvIsLoadedBySpringWithoutPrintingTheKey() {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(".env")));
        var environment = new org.springframework.core.env.StandardEnvironment();
        org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String key = environment.getProperty("app.chat.gemini.api-key");
        assertNotNull(key);
        assertFalse(key.isBlank());
        String configuredModel = environment.getProperty("APP_GEMINI_MODEL");
        assertNotNull(configuredModel);
        assertFalse(configuredModel.isBlank());
        assertEquals(configuredModel, environment.getProperty("app.chat.gemini.model"));
        assertEquals("rules", environment.getProperty("app.chat.mode"));
        assertEquals("false", environment.getProperty("app.chat.generate-reply"));
    }
}
