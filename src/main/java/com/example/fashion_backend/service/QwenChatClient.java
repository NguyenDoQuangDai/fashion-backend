package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QwenChatClient implements ChatModelClient {
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final int seed;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore gate = new Semaphore(1);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public QwenChatClient(@Value("${app.chat.base-url:http://127.0.0.1:8082}") String url,
            @Value("${app.chat.model:fashion-qwen3-4b}") String model,
            @Value("${app.chat.timeout-seconds:60}") int seconds,
            @Value("${app.chat.seed:42}") int seed) {
        endpoint = URI.create(url.replaceAll("/+$", "") + "/v1/chat/completions");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || seconds < 1 || seconds > 300)
            throw new IllegalArgumentException("Invalid chat endpoint/timeout");
        this.model = model; timeout = Duration.ofSeconds(seconds); this.seed = seed;
    }

    @Override
    public Completion complete(String system, String input, JsonNode schema, int tokens) {
        return complete(system, input, schema, tokens, timeout.toMillis());
    }
    @Override
    public Completion complete(String system, String input, JsonNode schema, int tokens, long remainingMs) {
        if (remainingMs <= 0) throw new Unavailable("timeout");
        if (!gate.tryAcquire()) throw new Unavailable("busy");
        long start = System.nanoTime();
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", input)));
            body.put("response_format", Map.of("type", "json_schema", "json_schema",
                    Map.of("name", "chat_result", "strict", true, "schema", schema)));
            body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            body.put("temperature", 0.7); body.put("top_p", 0.8); body.put("top_k", 20);
            body.put("seed", seed); body.put("max_tokens", tokens); body.put("stream", false);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(Math.min(remainingMs, timeout.toMillis())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes.length > 65536) throw new Unavailable("http_or_size");
            var root = json.readTree(bytes);
            var choice = root.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw new Unavailable("truncated");
            var content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank() || content.asText().contains("<think>"))
                throw new Unavailable("invalid_content");
            return new Completion(content.asText(), root.path("usage"), root.path("timings"),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new Unavailable("interrupted");
        } catch (Unavailable ex) { throw ex;
        } catch (Exception ex) { throw new Unavailable(ex instanceof HttpTimeoutException ? "timeout" : "connection_or_json");
        } finally { gate.release(); }
    }

}
