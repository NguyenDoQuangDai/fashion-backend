package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GeoapifyService {
    private final HttpClient http;
    private final String baseUrl, key, mode, bucket;
    private final Duration timeout;
    private final FallbackStore store;
    private final MeterRegistry metrics;
    private final Semaphore pending = new Semaphore(8);
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public GeoapifyService(@Value("${app.geoapify.base-url:https://api.geoapify.com}") String baseUrl,
            @Value("${app.geoapify.api-key:}") String key,
            @Value("${app.geoapify.timeout-seconds:4}") long timeout,
            @Value("${app.geoapify.routing-mode:scooter}") String mode,
            FallbackStore store, MeterRegistry metrics) {
        if (!Set.of("scooter", "motorcycle").contains(mode)) throw new IllegalArgumentException("Invalid Geoapify vehicle");
        URI uri = URI.create(baseUrl);
        if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost())))
                || uri.getRawQuery() != null || uri.getUserInfo() != null || timeout < 1 || timeout > 10)
            throw new IllegalArgumentException("Invalid Geoapify endpoint/timeout");
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.key = key; this.mode = mode;
        this.timeout = Duration.ofSeconds(timeout); this.store = store; this.metrics = metrics;
        this.bucket = "geoapify:" + hash(key);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }
    public String mode() { return mode; }
    public boolean configured() { return key != null && !key.isBlank(); }
    public JsonNode suggest(String text) {
        return get("/v1/geocode/autocomplete", Map.of("text", text, "lang", "vi", "filter", "countrycode:vn",
                "limit", "5", "format", "json"));
    }
    public ShippingService.ShippingQuote route(DeliveryAddressService.ResolvedAddress destination) {
        JsonNode root;
        try {
            root = get("/v1/routing", Map.of("waypoints", ShippingService.WAREHOUSE + "|"
                    + destination.latitude().toPlainString() + "," + destination.longitude().toPlainString(),
                    "mode", mode, "units", "metric", "format", "geojson"));
        } catch (AddressException ex) {
            throw new ShippingException(ex.getStatus(), ex.getCode().replace("ADDRESS_", "SHIPPING_"),
                    "Chưa lấy được phí vận chuyển. Vui lòng thử lại sau.", ex.getRetryAfter(), null);
        }
        JsonNode features = root.path("features");
        if (features.isArray() && features.isEmpty())
            throw new ShippingException(422, "SHIPPING_NO_ROAD_ROUTE", "Không tìm được tuyến xe máy tới địa chỉ này.");
        JsonNode route = features.path(0).path("properties");
        JsonNode distance = route.path("distance");
        if (!features.isArray() || !distance.isNumber() || !"meters".equalsIgnoreCase(route.path("distance_units").asText()))
            throw invalidRoute();
        BigDecimal meters = distance.decimalValue();
        if (meters.signum() < 0 || meters.compareTo(new BigDecimal("1000000000")) > 0) throw invalidRoute();
        return new ShippingService.ShippingQuote(meters, ShippingService.calculateFee(meters), "OK", "GEOAPIFY", mode);
    }
    private ShippingException invalidRoute() {
        return new ShippingException(502, "SHIPPING_INVALID_RESPONSE", "Dữ liệu tuyến đường không hợp lệ. Vui lòng thử lại.");
    }
    private JsonNode get(String path, Map<String, String> params) {
        if (!configured()) throw new AddressException(503, "ADDRESS_PROVIDER_NOT_CONFIGURED", "Dịch vụ địa chỉ chưa được cấu hình.", 60);
        if (!pending.tryAcquire()) throw limited(1);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            String cooldown = store.get(bucket + ":cooldown");
            if (cooldown != null) throw limited(Integer.parseInt(cooldown));
            // One slot per 250ms, shared by autocomplete and routing on every backend instance.
            boolean acquired = false;
            for (int attempt = 0; attempt < 4; attempt++) {
                CheckoutDeadline.remaining(timeout);
                if (store.acquire(bucket, 1, 250)) { acquired = true; break; }
                if (attempt < 3) Thread.sleep(Math.min(250, CheckoutDeadline.remaining(Duration.ofMillis(250)).toMillis()));
            }
            if (!acquired) throw limited(1);
            String query = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
            String requestId = java.util.UUID.randomUUID().toString();
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path + "?" + query + "&apiKey=" + encode(key)))
                    .timeout(CheckoutDeadline.remaining(timeout)).header("Accept", "application/json")
                    .header("X-Request-ID", requestId).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            metrics.counter("geoapify.requests", "endpoint", path, "status", Integer.toString(response.statusCode())).increment();
            if (response.statusCode() >= 400)
                org.slf4j.LoggerFactory.getLogger(GeoapifyService.class).warn(
                        "Checkout upstream error: provider=GEOAPIFY operation={} status={} requestId={}", path, response.statusCode(), requestId);
            if (response.statusCode() == 429) {
                int retry = retrySeconds(response.headers().firstValue("Retry-After").orElse(null));
                store.put(bucket + ":cooldown", Integer.toString(retry), Duration.ofSeconds(retry));
                throw limited(retry);
            }
            if (response.statusCode() == 400 && path.equals("/v1/routing")) {
                // Only the documented routing no-path condition is a business error; do not classify every 400 as no route.
                JsonNode error = mapper.readTree(response.body());
                if (error != null && error.path("message").asText().toLowerCase(java.util.Locale.ROOT).contains("no route"))
                    throw new ShippingException(422, "SHIPPING_NO_ROAD_ROUTE", "Không tìm được tuyến xe máy tới địa chỉ này.");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new AddressException(503, "ADDRESS_PROVIDER_NOT_CONFIGURED", "Dịch vụ địa chỉ tạm thời không khả dụng.", 60);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AddressException(503, "ADDRESS_PROVIDER_UNAVAILABLE", "Dịch vụ địa chỉ tạm thời không khả dụng.");
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            outcome = "ok";
            return result;
        } catch (AddressException | ShippingException ex) { throw ex;
        } catch (HttpTimeoutException ex) {
            throw new AddressException(504, "ADDRESS_TIMEOUT", "Dịch vụ địa chỉ phản hồi chậm. Vui lòng thử lại.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AddressException(504, "ADDRESS_TIMEOUT", "Yêu cầu địa chỉ bị gián đoạn. Vui lòng thử lại.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AddressException(502, "ADDRESS_INVALID_RESPONSE", "Không lấy được dữ liệu địa chỉ hợp lệ.");
        } catch (java.io.IOException ex) {
            throw new AddressException(503, "ADDRESS_PROVIDER_UNAVAILABLE", "Không thể kết nối dịch vụ địa chỉ. Vui lòng thử lại.");
        } catch (Exception ex) {
            // Deliberately omit the original cause: HTTP exceptions can contain apiKey URLs.
            throw new AddressException(502, "ADDRESS_INVALID_RESPONSE", "Không lấy được dữ liệu địa chỉ hợp lệ.");
        } finally {
            pending.release();
            metrics.timer("geoapify.latency", "endpoint", path, "outcome", outcome)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    static int retrySeconds(String value) {
        try { return Math.max(1, Math.min(3600, Integer.parseInt(value))); }
        catch (Exception ignored) {
            try { return (int) Math.max(1, Math.min(3600, java.time.Duration.between(java.time.Instant.now(),
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds())); }
            catch (Exception invalid) { return 60; }
        }
    }
    private AddressException limited(int retry) {
        return new AddressException(503, "ADDRESS_RATE_LIMITED", "Dịch vụ đang quá tải. Vui lòng thử lại sau.", retry);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
