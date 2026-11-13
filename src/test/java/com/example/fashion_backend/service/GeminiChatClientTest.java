package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.chat.ChatCriteria;
import com.example.fashion_backend.dto.chat.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiChatClientTest {
    private final ObjectMapper json = new ObjectMapper();

    private static class Server implements AutoCloseable {
        final HttpServer server;
        Server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1beta/models/gemini-3.8-flash:generateContent", handler);
            server.start();
        }
        URI root() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"); }
        @Override public void close() { server.stop(0); }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private String completion(String content, String finish) throws IOException {
        var root = json.createObjectNode();
        root.put("modelVersion", "gemini-3.8-flash");
        var usage = root.putObject("usageMetadata");
        usage.put("promptTokenCount", 31);
        usage.put("candidatesTokenCount", 19);
        usage.put("thoughtsTokenCount", 7);
        usage.put("totalTokenCount", 57);
        var candidate = root.putArray("candidates").addObject();
        candidate.put("finishReason", finish);
        candidate.putObject("content").putArray("parts").addObject().put("text", content);
        return json.writeValueAsString(root);
    }

    @Test void sendsCurrentSchemaAndParsesThroughUnchangedJavaPipeline() throws Exception {
        var seen = new AtomicReference<JsonNode>();
        var keyHeader = new AtomicReference<String>();
        var criteria = new ChatCriteria("product_search", "ao", "thun", "nam", null, null,
            true, true, List.of(), List.of(), null, List.of(), null, 5, "relevance", false, null);
        String response = completion(json.writeValueAsString(criteria), "STOP");
        try (var server = new Server(exchange -> {
            seen.set(json.readTree(exchange.getRequestBody()));
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            respond(exchange, 200, response);
        })) {
            var client = new GeminiChatClient(server.root(), "synthetic-test-key", "gemini-3.8-flash", 2, 0);
            var parser = new ChatCriteriaParser(client);
            var request = new ChatRequest();
            request.setMessage("Tìm áo phông nam");
            var result = parser.parse(request);
            assertEquals(criteria, result.criteria());
            assertEquals("ok", result.reasonCode());
            assertEquals(31, result.completion().usage().path("promptTokenCount").asInt());
            assertEquals(19, result.completion().usage().path("candidatesTokenCount").asInt());
            assertEquals(7, result.completion().usage().path("thoughtsTokenCount").asInt());
            assertEquals("gemini-3.8-flash", client.modelId());
            assertEquals("gemini-3.8-flash", client.lastModelVersion());
            assertEquals("synthetic-test-key", keyHeader.get());
            var wire = seen.get();
            assertEquals("Tìm áo phông nam", json.readTree(wire.path("contents").get(0).path("parts").get(0)
                .path("text").asText()).path("message").asText());
            assertEquals("user", wire.path("contents").get(0).path("role").asText());
            assertTrue(wire.path("systemInstruction").path("parts").get(0).path("text").asText().contains("JSON schema:"));
            var config = wire.path("generationConfig");
            assertEquals("APPLICATION_JSON", config.path("responseFormat").path("text").path("mimeType").asText());
            JsonNode sentSchema = config.path("responseFormat").path("text").path("schema");
            assertEquals(parser.schema(), sentSchema);
            assertEquals(17, sentSchema.path("required").size());
            assertFalse(sentSchema.path("additionalProperties").asBoolean(true));
            assertEquals(512, config.path("maxOutputTokens").asInt());
            assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asText());
            assertEquals(42, config.path("seed").asInt());
            assertFalse(wire.toString().contains("synthetic-test-key"));
        }
    }

    @Test void retriesTransient503And429ButNeverDailyQuota() throws Exception {
        var attempts = new AtomicInteger();
        String ok = completion("{}", "STOP");
        try (var server = new Server(exchange -> {
            if (attempts.incrementAndGet() == 1) respond(exchange, 503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
            else respond(exchange, 200, ok);
        })) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 3, 1);
            assertEquals("{}", client.complete("s", "i", json.createObjectNode(), 256).content());
            assertEquals(2, attempts.get());
        }
        attempts.set(0);
        try (var server = new Server(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
            } else respond(exchange, 200, ok);
        })) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 3, 1);
            assertEquals("{}", client.complete("s", "i", json.createObjectNode(), 256).content());
            assertEquals(2, attempts.get());
        }
        attempts.set(0);
        try (var server = new Server(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}");
        })) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 3, 1);
            assertEquals("quota_exhausted", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
            assertEquals(1, attempts.get());
        }
    }

    @Test void rejectsBlockedTruncatedMalformedAndOversizedResponses() throws Exception {
        for (String finish : List.of("MAX_TOKENS", "SAFETY", "RECITATION")) {
            try (var server = new Server(exchange -> respond(exchange, 200, completion("{}", finish)))) {
                var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
                assertEquals(finish.equals("MAX_TOKENS") ? "truncated" : "blocked",
                    assertThrows(ChatModelClient.Unavailable.class,
                        () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
            }
        }
        try (var server = new Server(exchange -> respond(exchange, 200,
            "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"candidates\":[]}"))) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
            assertEquals("blocked", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
        }
        try (var server = new Server(exchange -> respond(exchange, 200, completion("{", "STOP")))) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
            var parser = new ChatCriteriaParser(client);
            var request = new ChatRequest(); request.setMessage("Áo polo nam");
            assertEquals("schema_error", assertThrows(ChatModelClient.Unavailable.class,
                () -> parser.parse(request)).reasonCode());
        }
        try (var server = new Server(exchange -> respond(exchange, 200, "{} {}"))) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
            assertEquals("invalid_content", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
        }
        try (var server = new Server(exchange -> respond(exchange, 200, "x".repeat(65_537)))) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
            assertEquals("response_too_large", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
        }
    }

    @Test void enforcesDeadlineInputLimitsAndNonRetryableErrors() throws Exception {
        var attempts = new AtomicInteger();
        try (var server = new Server(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}");
        })) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 1);
            assertEquals("schema_error", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256)).reasonCode());
            assertEquals(1, attempts.get());
            assertEquals("request_too_large", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "x".repeat(16_385), json.createObjectNode(), 256)).reasonCode());
            assertEquals("model_timeout", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256, 0)).reasonCode());
            assertEquals(1, attempts.get());
        }
        try (var server = new Server(exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            var client = new GeminiChatClient(server.root(), "test-key", "gemini-3.8-flash", 2, 0);
            assertEquals("model_timeout", assertThrows(ChatModelClient.Unavailable.class,
                () -> client.complete("s", "i", json.createObjectNode(), 256, 100)).reasonCode());
        }
        assertThrows(IllegalArgumentException.class,
            () -> new GeminiChatClient(URI.create("https://generativelanguage.googleapis.com/v1beta"), "", "gemini-3.8-flash", 2, 0));
    }

    @Test void springChoosesGeminiOnlyWhenModeIsGemini() {
        var runner = new ApplicationContextRunner()
            .withUserConfiguration(QwenChatClient.class, GeminiChatClient.class)
            .withPropertyValues("app.chat.gemini.api-key=synthetic-test-key", "app.chat.gemini.model=gemini-3.8-flash");
        runner.withPropertyValues("app.chat.mode=rules").run(context -> {
            assertFalse(context.containsBean("geminiChatClient"));
            assertSame(context.getBean(QwenChatClient.class), context.getBean(ChatModelClient.class));
        });
        runner.withPropertyValues("app.chat.mode=gemini").run(context -> {
            assertSame(context.getBean(GeminiChatClient.class), context.getBean(ChatModelClient.class));
            assertEquals("gemini-3.8-flash", context.getBean(GeminiChatClient.class).modelId());
        });
    }

    @Test void blockedExtractionNeverRetrievesProducts() {
        var parser = mock(ChatCriteriaParser.class);
        var search = mock(ChatProductSearchService.class);
        when(parser.parse(any())).thenThrow(new ChatModelClient.Unavailable("blocked"));
        var service = new ChatService(parser, new ChatRuleFallback(), search, mock(ChatReplyService.class), "gemini", 60);
        var request = new ChatRequest(); request.setMessage("Áo polo nam");
        assertTrue(service.chat(request).getSuggestedProducts().isEmpty());
        verifyNoInteractions(search);
    }
}
