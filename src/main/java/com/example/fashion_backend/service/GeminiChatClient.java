package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Gemini transport only; ChatCriteriaParser remains the authority for JSON and business validation. */
@Component
@Primary
@ConditionalOnProperty(name = "app.chat.mode", havingValue = "gemini")
public class GeminiChatClient implements ChatModelClient {
    private static final URI API_ROOT = URI.create("https://generativelanguage.googleapis.com/v1beta");
    private static final int MAX_BODY_BYTES = 65_536;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final int maxRetries;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Semaphore gate = new Semaphore(1);
    private volatile String lastModelVersion;

    @Autowired
    public GeminiChatClient(@Value("${app.chat.gemini.api-key:}") String apiKey,
            @Value("${app.chat.gemini.model:gemini-3.8-flash}") String model,
            @Value("${app.chat.timeout-seconds:60}") int seconds) {
        this(API_ROOT, apiKey, model, seconds, 1);
    }

    // Package-private endpoint override is only used by the mock HTTP contract tests.
    GeminiChatClient(URI root, String apiKey, String model, int seconds, int maxRetries) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n"))
            throw new IllegalArgumentException("Gemini API key is missing or invalid");
        if (model == null || !model.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid Gemini model ID");
        if (seconds < 1 || seconds > 300 || maxRetries < 0 || maxRetries > 2)
            throw new IllegalArgumentException("Invalid Gemini timeout/retry configuration");
        if (!"https".equals(root.getScheme()) && !("http".equals(root.getScheme())
                && ("127.0.0.1".equals(root.getHost()) || "localhost".equals(root.getHost()))))
            throw new IllegalArgumentException("Invalid Gemini endpoint");
        this.endpoint = URI.create(root.toString().replaceAll("/+$", "") + "/models/" + model + ":generateContent");
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = seconds * 1000L;
        this.maxRetries = maxRetries;
    }

    public String modelId() { return model; }
    public String lastModelVersion() { return lastModelVersion; }

    @Override
    public Completion complete(String system, String input, JsonNode schema, int tokens) {
        return complete(system, input, schema, tokens, timeoutMs);
    }

    @Override
    public Completion complete(String system, String input, JsonNode schema, int tokens, long remainingMs) {
        if (remainingMs <= 0) throw new Unavailable("timeout");
        if (system == null || input == null || schema == null || tokens < 1 || tokens > 2048
                || system.length() > 32_768 || input.length() > 16_384 || schema.toString().length() > 65_536)
            throw new Unavailable("request_too_large");
        if (!gate.tryAcquire()) throw new Unavailable("busy");
        long started = System.nanoTime();
        long budgetMs = Math.min(remainingMs, timeoutMs);
        try {
            byte[] body = json.writeValueAsBytes(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input)))),
                "generationConfig", Map.of(
                    "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", schema)),
                    "maxOutputTokens", tokens,
                    "temperature", 0.7,
                    "topP", 0.8,
                    "topK", 20,
                    "seed", 42,
                    "thinkingConfig", Map.of("thinkingLevel", "LOW"))));
            if (body.length > MAX_BODY_BYTES) throw new Unavailable("request_too_large");
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                long leftMs = budgetMs - elapsed(started);
                if (leftMs <= 0) throw new Unavailable("timeout");
                var request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(Math.max(1, leftMs)))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                try {
                    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.body().length > MAX_BODY_BYTES) throw new Unavailable("response_too_large");
                    int status = response.statusCode();
                    if (status == 200) return decode(response.body(), started);
                    JsonNode error = readError(response.body());
                    if (status == 429) {
                        String reason = dailyQuota(error) ? "quota_exhausted" : "rate_limited";
                        if (reason.equals("quota_exhausted")) throw new Unavailable(reason);
                        retryOrFail(attempt, started, budgetMs, response.headers().firstValue("Retry-After").orElse(null), reason);
                    } else if (status == 408 || status == 500 || status == 502 || status == 503 || status == 504) {
                        retryOrFail(attempt, started, budgetMs, response.headers().firstValue("Retry-After").orElse(null),
                            status == 408 || status == 504 ? "model_timeout" : "provider_unavailable");
                    } else if (status == 401 || status == 403) throw new Unavailable("auth_error");
                    else if (status == 400) throw new Unavailable("schema_error");
                    else if (status == 404) throw new Unavailable("model_not_found");
                    else throw new Unavailable("provider_http_error");
                } catch (HttpTimeoutException ex) {
                    retryOrFail(attempt, started, budgetMs, null, "model_timeout");
                } catch (IOException ex) {
                    retryOrFail(attempt, started, budgetMs, null, "provider_unavailable");
                }
            }
            throw new Unavailable("provider_unavailable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted");
        } catch (IOException ex) {
            throw new Unavailable("serialization_error");
        } finally {
            gate.release();
        }
    }

    private Completion decode(byte[] body, long started) throws IOException {
        JsonNode root;
        try { root = json.readTree(body); }
        catch (IOException ex) { throw new Unavailable("invalid_content"); }
        if (root == null || !root.isObject()) throw new Unavailable("invalid_content");
        if (!root.path("promptFeedback").path("blockReason").asText("").isBlank()) throw new Unavailable("blocked");
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) throw new Unavailable("invalid_content");
        JsonNode candidate = candidates.get(0);
        String finish = candidate.path("finishReason").asText("");
        if (finish.equals("MAX_TOKENS")) throw new Unavailable("truncated");
        if (List.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "LANGUAGE")
                .contains(finish)) throw new Unavailable("blocked");
        if (!finish.equals("STOP")) throw new Unavailable("invalid_content");
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) throw new Unavailable("invalid_content");
        var content = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) continue;
            if (!part.path("text").isTextual()) throw new Unavailable("invalid_content");
            content.append(part.path("text").asText());
            if (content.length() > MAX_BODY_BYTES) throw new Unavailable("response_too_large");
        }
        if (content.isEmpty() || content.toString().isBlank()) throw new Unavailable("invalid_content");
        lastModelVersion = root.path("modelVersion").asText(null);
        JsonNode usage = root.path("usageMetadata");
        return new Completion(content.toString(), usage.isMissingNode() ? null : usage.deepCopy(), null, elapsed(started));
    }

    private JsonNode readError(byte[] bytes) {
        try { return json.readTree(bytes); }
        catch (IOException ex) { return json.nullNode(); }
    }

    private boolean dailyQuota(JsonNode root) {
        String code = root.path("error").path("code").asText("");
        if ("quota_exceeded".equalsIgnoreCase(code)) return true;
        for (JsonNode detail : root.path("error").path("details"))
            for (JsonNode violation : detail.path("violations")) {
                String id = (violation.path("quotaId").asText("") + " " + violation.path("quotaMetric").asText(""))
                    .toLowerCase(Locale.ROOT);
                if (id.contains("perday") || id.contains("per_day") || id.contains("requests_per_day")) return true;
            }
        return false;
    }

    private void retryOrFail(int attempt, long started, long budgetMs, String retryAfter, String reason)
            throws InterruptedException {
        if (attempt >= maxRetries) throw new Unavailable(reason);
        long delay = retryDelayMs(retryAfter, attempt);
        if (delay >= budgetMs - elapsed(started)) throw new Unavailable(reason);
        Thread.sleep(delay);
    }

    private long retryDelayMs(String value, int attempt) {
        if (value != null) {
            try { return Math.max(0, Duration.ofSeconds(Long.parseLong(value.trim())).toMillis()); }
            catch (RuntimeException ignored) { /* Retry-After may be an HTTP date. */ }
            try { return Math.max(0, Duration.between(java.time.Instant.now(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
            catch (RuntimeException ignored) { /* Fall back to bounded exponential delay. */ }
        }
        return (250L << attempt) + ThreadLocalRandom.current().nextLong(100);
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
