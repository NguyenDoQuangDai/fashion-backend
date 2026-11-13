package com.example.fashion_backend.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GogodukService {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final Duration requestTimeout;

    public GogodukService(
            @Value("${app.gogoduk.base-url:https://api.gogoduk.com}") String baseUrl,
            @Value("${app.gogoduk.api-key:}") String apiKey,
            @Value("${app.gogoduk.timeout-seconds:8}") long timeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public UpstreamResponse suggest(String input, String lang, String country, String sessionToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("input", input);
        params.put("lang", lang);
        params.put("country", country);
        params.put("sessionToken", sessionToken);
        return get("/v1/suggest", params, sessionToken);
    }

    public UpstreamResponse resolve(String placeId, String lang, String sessionToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", placeId);
        params.put("lang", lang);
        params.put("sessionToken", sessionToken);
        return get("/v1/place/resolve", params, sessionToken);
    }

    public UpstreamResponse directions(String origin, String destination) {
        return get("/v1/directions", Map.of("origin", origin, "destination", destination,
                "vehicle", "motobike"), null);
    }

    private UpstreamResponse get(String path, Map<String, String> params, String sessionToken) {
        if (apiKey == null || apiKey.isBlank()) {
            return new UpstreamResponse(503, "{\"error\":\"GoGoDuk chưa được cấu hình\",\"code\":\"GOGODUK_NOT_CONFIGURED\"}", null, null);
        }

        try {
            String requestId = java.util.UUID.randomUUID().toString();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path + "?" + encodeParams(params)))
                    .timeout(CheckoutDeadline.remaining(requestTimeout))
                    .header("Accept", "application/json")
                    .header("X-API-Key", apiKey)
                    .header("X-Request-ID", requestId);
            if (sessionToken != null && !sessionToken.isBlank()) {
                requestBuilder.header("X-Session-Token", sessionToken);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400)
                org.slf4j.LoggerFactory.getLogger(GogodukService.class).warn(
                        "Checkout upstream error: provider=GOGODUK operation={} status={} requestId={}", path, response.statusCode(), requestId);
            return new UpstreamResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("X-Request-ID").orElse(null),
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (java.net.http.HttpTimeoutException exception) {
            return new UpstreamResponse(504, "{\"error\":\"GoGoDuk hết thời gian phản hồi\",\"code\":\"GOGODUK_TIMEOUT\"}", null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new UpstreamResponse(504, "{\"error\":\"GoGoDuk request bị gián đoạn\",\"code\":\"GOGODUK_INTERRUPTED\"}", null, null);
        } catch (Exception exception) {
            return new UpstreamResponse(503, "{\"error\":\"Không thể kết nối GoGoDuk\",\"code\":\"GOGODUK_UNAVAILABLE\"}", null, null);
        }
    }

    private String encodeParams(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    public record UpstreamResponse(int statusCode, String body, String requestId, String retryAfter) {}
}
