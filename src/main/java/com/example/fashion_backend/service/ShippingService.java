package com.example.fashion_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class ShippingService {
    public static final String WAREHOUSE = "20.9986611,105.805369";
    public static final String VEHICLE = "motobike";
    private final GogodukService gogoduk;
    private final GeoapifyService geoapify;
    private final ProviderFallback fallback;
    private final FallbackStore store;
    private final Object[] locks = java.util.stream.IntStream.range(0,64).mapToObj(i -> new Object()).toArray();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public ShippingService(GogodukService gogoduk) { this.gogoduk = gogoduk; this.geoapify=null; this.fallback=null; this.store=null; }

    @org.springframework.beans.factory.annotation.Autowired
    public ShippingService(GogodukService gogoduk, GeoapifyService geoapify, ProviderFallback fallback, FallbackStore store) {
        this.gogoduk=gogoduk; this.geoapify=geoapify; this.fallback=fallback; this.store=store;
    }

    public ShippingQuote quote(DeliveryAddressService.ResolvedAddress destination) {
        if (geoapify == null) return primaryQuote(destination);
        String key = "route:v2:" + GeoapifyService.hash(WAREHOUSE + "|" + destination.latitude().toPlainString()
                + "|" + destination.longitude().toPlainString() + "|" + geoapify.mode() + "|" + fallback.modeName("routing"));
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            String cached = store.get(key);
            if (cached != null) {
                try { ShippingQuote quote = mapper.readValue(cached, ShippingQuote.class); fallback.cacheHit("routing"); return quote; }
                catch (Exception ignored) { /* rebuild an invalid cache entry */ }
            }
            ShippingException last = new ShippingException(503, "SHIPPING_PROVIDER_UNAVAILABLE", "Chưa lấy được phí vận chuyển. Vui lòng thử lại.");
            String previous = null;
            for (String provider : fallback.providers("routing")) {
                if (!fallback.allowed(provider, "routing")) { previous = provider; continue; }
                CheckoutDeadline.remaining(java.time.Duration.ofSeconds(1));
                if (previous != null) fallback.switching(previous, provider, "routing");
                try {
                    ShippingQuote quote = fallback.observe(provider, "routing", "route", () ->
                            "GEOAPIFY".equals(provider) ? geoapify.route(destination) : primaryQuote(destination));
                    fallback.success(provider, "routing");
                    return cache(key, quote);
                } catch (ShippingException ex) {
                    if (ex.getStatus() == 422 || ex.getStatus() == 400 || Thread.currentThread().isInterrupted()) throw ex;
                    fallback.failure(provider, "routing", ex.getRetryAfter(), "route", ex.getCode());
                    last = ex;
                    previous = provider;
                }
            }
            throw last;
        }
    }

    private ShippingQuote cache(String key, ShippingQuote quote) {
        try { store.put(key, mapper.writeValueAsString(quote), java.time.Duration.ofMinutes(5)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        return quote;
    }

    private ShippingQuote primaryQuote(DeliveryAddressService.ResolvedAddress destination) {
        var response = gogoduk.directions(WAREHOUSE,
                destination.latitude().toPlainString() + "," + destination.longitude().toPlainString());
        if (response.statusCode() == 401 || response.statusCode() == 403 || (response.statusCode() == 503
                && response.body() != null && response.body().contains("\"code\":\"GOGODUK_NOT_CONFIGURED\"")))
            throw new ShippingException(503, "SHIPPING_PROVIDER_NOT_CONFIGURED", "Dịch vụ tìm đường tạm thời không khả dụng.", 60, null);
        if (response.statusCode() == 429) {
            Integer retryAfter = null;
            try {
                String raw = response.retryAfter() != null ? response.retryAfter()
                        : mapper.readTree(response.body()).path("retry_after").asText();
                retryAfter = Math.max(1, Math.min(3600, Integer.parseInt(raw)));
            }
            catch (Exception ignored) { /* Header is optional; do not guess a provider retry interval. */ }
            throw new ShippingException(503, "SHIPPING_RATE_LIMITED",
                    "Dịch vụ báo phí đang quá tải. Vui lòng thử lại sau hoặc liên hệ cửa hàng.", retryAfter, null);
        }
        if (response.statusCode() == 504) {
            throw new ShippingException(504, "SHIPPING_TIMEOUT", "Không lấy được tuyến đường trong thời gian cho phép. Vui lòng thử lại hoặc liên hệ cửa hàng.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ShippingException(503, "SHIPPING_PROVIDER_UNAVAILABLE", "Dịch vụ tìm đường hiện không khả dụng. Vui lòng thử lại sau hoặc liên hệ cửa hàng.");
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            String status = root.path("status").asText();
            if ("ZERO_RESULTS".equals(status)) throw noRoute();
            if (!"OK".equals(status) && !"ESTIMATED".equals(status)) throw invalidResponse();
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) throw noRoute();
            // Use the provider's first recommended road route, summing its legs in meters.
            JsonNode legs = routes.get(0).path("legs");
            if (!legs.isArray() || legs.isEmpty()) throw invalidResponse();
            BigDecimal distance = BigDecimal.ZERO;
            for (JsonNode leg : legs) {
                JsonNode value = leg.path("distance").path("value");
                if (!value.isNumber()) throw invalidResponse();
                BigDecimal meters = value.decimalValue();
                if (meters.signum() < 0) throw invalidResponse();
                distance = distance.add(meters);
            }
            if (distance.compareTo(new BigDecimal("1000000000")) > 0) throw invalidResponse();
            return new ShippingQuote(distance, calculateFee(distance), status);
        } catch (ShippingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    public static BigDecimal calculateFee(BigDecimal meters) {
        if (meters == null || meters.signum() < 0) throw new IllegalArgumentException("Invalid route distance");
        return meters.subtract(new BigDecimal("5000")).max(BigDecimal.ZERO)
                .divide(new BigDecimal("1000"), 0, RoundingMode.CEILING)
                .multiply(new BigDecimal("3000")).min(new BigDecimal("50000"));
    }

    private ShippingException noRoute() {
        return new ShippingException(422, "SHIPPING_NO_ROAD_ROUTE", "Chưa tìm được tuyến đường xe máy hợp lệ đến địa chỉ này. Vui lòng chọn địa chỉ khác, thử lại hoặc liên hệ cửa hàng để được hỗ trợ.");
    }

    private ShippingException invalidResponse() {
        return new ShippingException(502, "SHIPPING_INVALID_RESPONSE", "Dịch vụ tìm đường trả dữ liệu không hợp lệ. Vui lòng thử lại hoặc chọn địa chỉ khác.");
    }

    public record ShippingQuote(BigDecimal distanceMeters, BigDecimal shippingFee, String routeStatus, String provider, String vehicle) {
        public ShippingQuote(BigDecimal distanceMeters, BigDecimal shippingFee, String routeStatus) {
            this(distanceMeters, shippingFee, routeStatus, "GOGODUK", VEHICLE);
        }
        public ShippingQuote(BigDecimal distanceMeters, BigDecimal shippingFee) {
            this(distanceMeters, shippingFee, "OK");
        }
    }
}
