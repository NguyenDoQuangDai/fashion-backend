package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AddressLookupService {
    private final GogodukService gogoduk;
    private final GeoapifyService geoapify;
    private final ProviderFallback fallback;
    private final FallbackStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    public AddressLookupService(GogodukService gogoduk, GeoapifyService geoapify, ProviderFallback fallback, FallbackStore store) {
        this.gogoduk = gogoduk; this.geoapify = geoapify; this.fallback = fallback; this.store = store;
    }
    public ObjectNode suggest(String input, String session) {
        validateSession(session);
        if (input == null || input.trim().length() < 3 || input.length() > 200)
            throw new AddressException(400, "ADDRESS_INVALID_INPUT", "Vui lòng nhập từ 3 đến 200 ký tự địa chỉ.");
        AddressException last = unavailable();
        String previous = null;
        for (String provider : fallback.providers("address")) {
            if (!fallback.allowed(provider, "address")) { previous = provider; continue; }
            CheckoutDeadline.remaining(Duration.ofSeconds(1));
            if (previous != null) fallback.switching(previous, provider, "address");
            try {
                ObjectNode result = fallback.observe(provider, "address", "suggest", () ->
                        "GEOAPIFY".equals(provider) ? geoSuggest(input.trim(), session) : gogodukSuggest(input.trim(), session));
                fallback.success(provider, "address");
                return result;
            } catch (AddressException ex) {
                if (ex.getStatus() == 400 || ex.getStatus() == 422 || Thread.currentThread().isInterrupted()) throw ex;
                fallback.failure(provider, "address", ex.getRetryAfter(), "suggest", ex.getCode());
                last = ex;
                previous = provider;
            }
        }
        throw last;
    }
    private ObjectNode gogodukSuggest(String input, String session) {
        var response = gogoduk.suggest(input, "vi", "VN", session);
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw upstreamError(response);
        try {
            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.path("predictions").isArray()) throw new IllegalArgumentException();
            for (JsonNode prediction : root.path("predictions")) {
                if (!prediction.isObject() || prediction.path("placeId").asText().isBlank()) throw new IllegalArgumentException();
                ((ObjectNode) prediction).put("source", "GOGODUK");
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("predictions", root.path("predictions")); result.put("source", "GOGODUK");
            return result;
        } catch (Exception ex) { throw invalidResponse(); }
    }
    private ObjectNode geoSuggest(String input, String session) {
        String cacheKey = "geo:suggest:" + GeoapifyService.hash(input.trim() + "|vi|vn");
        ArrayNode addresses;
        synchronized (locks[Math.floorMod(cacheKey.hashCode(), locks.length)]) {
            String cached = store.get(cacheKey);
            if (cached != null) {
                fallback.cacheHit("suggest");
                try { addresses = (ArrayNode) mapper.readTree(cached); }
                catch (Exception ex) { throw unavailable(); }
            } else {
                JsonNode root = geoapify.suggest(input.trim());
                if (!root.path("results").isArray()) throw invalidResponse();
                addresses = mapper.createArrayNode();
                for (JsonNode entry : root.path("results")) {
                    if (addresses.size() >= 5) break;
                    ObjectNode address = normalize(entry);
                    if (address != null) addresses.add(address);
                }
                store.put(cacheKey, addresses.toString(), Duration.ofMinutes(10));
            }
        }
        ArrayNode predictions = mapper.createArrayNode();
        for (JsonNode address : addresses) {
            String token = UUID.randomUUID().toString();
            store.put(selectionKey(token, session), address.toString(), Duration.ofMinutes(15));
            ObjectNode prediction = predictions.addObject();
            prediction.put("placeId", address.path("placeId").asText());
            prediction.put("mainText", address.path("address").asText());
            prediction.put("source", "GEOAPIFY"); prediction.put("selectionToken", token);
        }
        ObjectNode result = mapper.createObjectNode(); result.set("predictions", predictions); result.put("source", "GEOAPIFY");
        return result;
    }
    public JsonNode resolve(String placeId, String source, String token, String session) {
        validateSession(session);
        if (!Set.of("GEOAPIFY", "GOGODUK").contains(source == null ? "" : source))
            throw new AddressException(400, "ADDRESS_INVALID_SOURCE", "Nguồn địa chỉ không hợp lệ.");
        if ("GEOAPIFY".equals(source) || token != null) return selection(token, session, placeId, source);
        // A place ID cannot be resolved by another provider. Preserve transient errors for retry.
        if (!fallback.allowed("GOGODUK", "address")) throw unavailable();
        CheckoutDeadline.remaining(Duration.ofSeconds(1));
        return fallback.observe("GOGODUK", "address", "resolve", () -> resolveGogoduk(placeId, session));
    }
    private JsonNode resolveGogoduk(String placeId, String session) {
        var response = gogoduk.resolve(placeId, "vi", session);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            AddressException error = upstreamError(response);
            fallback.failure("GOGODUK", "address", error.getRetryAfter(), "resolve", error.getCode());
            throw error;
        }
        ObjectNode value;
        try {
            value = (ObjectNode) mapper.readTree(response.body()).path("result");
            if (value.path("placeId").asText().isBlank()) value.put("placeId", placeId);
            if (!placeId.equals(value.path("placeId").asText()) || !DeliveryAddressService.validResolved(
                    value.path("address").asText(), value.path("placeId").asText(),
                    coordinate(value.path("lat")), coordinate(value.path("lon")))) throw new IllegalArgumentException();
        } catch (Exception ignored) {
            fallback.failure("GOGODUK", "address", null, "resolve", "ADDRESS_INVALID_RESPONSE");
            throw invalidResponse();
        }
        value.put("source", "GOGODUK");
        String selectionToken = UUID.randomUUID().toString();
        store.put(selectionKey(selectionToken, session), value.toString(), Duration.ofMinutes(15));
        value.put("selectionToken", selectionToken);
        fallback.success("GOGODUK", "address");
        return value;
    }
    private AddressException upstreamError(GogodukService.UpstreamResponse response) {
        int status = response.statusCode();
        if (status == 429) return new AddressException(503, "ADDRESS_RATE_LIMITED", "Dịch vụ địa chỉ đang quá tải. Vui lòng thử lại.", GeoapifyService.retrySeconds(response.retryAfter()));
        if (status == 504) return new AddressException(504, "ADDRESS_TIMEOUT", "Dịch vụ địa chỉ phản hồi chậm. Vui lòng thử lại.");
        if (status == 401 || status == 403 || (status == 503 && response.body() != null && response.body().contains("\"code\":\"GOGODUK_NOT_CONFIGURED\"")))
            return new AddressException(503, "ADDRESS_PROVIDER_NOT_CONFIGURED", "Dịch vụ địa chỉ tạm thời không khả dụng.", 60);
        // Unclassified upstream 4xx must not discard a customer's selection.
        return unavailable();
    }
    public JsonNode selection(String token, String session, String placeId, String source) {
        JsonNode selected = selection(token, session, placeId);
        if (!source.equals(selected.path("source").asText())) throw reselect();
        return selected;
    }
    public JsonNode selection(String token, String session, String placeId) {
        validateSession(session);
        if (token == null || !token.matches("[0-9a-fA-F-]{36}")) throw reselect();
        String value = store.get(selectionKey(token, session));
        if (value == null) throw reselect();
        fallback.cacheHit("selection");
        try {
            JsonNode selected = mapper.readTree(value);
            if (!selected.path("placeId").asText().equals(placeId)) throw reselect();
            ((ObjectNode) selected).put("selectionToken", token);
            return selected;
        } catch (AddressException ex) { throw ex; } catch (Exception ex) { throw reselect(); }
    }
    private ObjectNode normalize(JsonNode entry) {
        try {
            if (!"vn".equalsIgnoreCase(entry.path("country_code").asText())
                    || !Set.of("building", "street", "amenity").contains(entry.path("result_type").asText())) return null;
            String text = entry.path("formatted").asText();
            BigDecimal lat = coordinate(entry.path("lat")), lon = coordinate(entry.path("lon"));
            String id = "geoapify:" + GeoapifyService.hash(text + "|" + lat + "|" + lon);
            if (!DeliveryAddressService.validResolved(text, id, lat, lon)) return null;
            ObjectNode value = mapper.createObjectNode();
            value.put("address", text); value.put("placeId", id); value.put("source", "GEOAPIFY");
            value.put("lat", lat.setScale(7, RoundingMode.HALF_UP)); value.put("lon", lon.setScale(7, RoundingMode.HALF_UP));
            if (!entry.path("city").asText().isBlank()) value.put("city", entry.path("city").asText());
            value.put("country", "Việt Nam"); return value;
        } catch (Exception ex) { return null; }
    }
    private BigDecimal coordinate(JsonNode node) {
        if (!node.isNumber() && !node.isTextual()) throw new IllegalArgumentException();
        return new BigDecimal(node.asText());
    }
    private static void validateSession(String session) {
        if (session == null || session.isBlank() || session.length() > 200)
            throw new AddressException(400, "ADDRESS_SESSION_REQUIRED", "Vui lòng tìm và chọn lại địa chỉ.");
    }
    private String selectionKey(String token, String session) { return "geo:selection:" + GeoapifyService.hash(session + ":" + token); }
    private AddressException unavailable() { return new AddressException(503, "ADDRESS_PROVIDER_UNAVAILABLE", "Chưa tìm được địa chỉ. Vui lòng thử lại sau."); }
    private AddressException invalidResponse() { return new AddressException(502, "ADDRESS_INVALID_RESPONSE", "Dịch vụ trả dữ liệu địa chỉ không hợp lệ."); }
    private AddressException reselect() { return new AddressException(422, "ADDRESS_RESELECT_REQUIRED", "Vui lòng tìm và chọn lại địa chỉ giao hàng."); }
}
