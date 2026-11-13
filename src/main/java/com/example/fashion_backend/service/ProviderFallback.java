package com.example.fashion_backend.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderFallback {
    private final String addressMode, shippingMode;
    private final Clock clock;
    private io.micrometer.core.instrument.MeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public ProviderFallback(@Value("${app.address.provider-mode:geoapify-first}") String addressMode,
            @Value("${app.shipping.provider-mode:geoapify-first}") String shippingMode) {
        this(addressMode, shippingMode, Clock.systemUTC());
    }
    ProviderFallback(String addressMode, String shippingMode, Clock clock) {
        this.addressMode = valid(addressMode); this.shippingMode = valid(shippingMode); this.clock = clock;
    }
    @org.springframework.beans.factory.annotation.Autowired
    void setMetrics(io.micrometer.core.instrument.MeterRegistry metrics) { this.metrics = metrics; }
    public <T> T observe(String provider, String capability, String operation, java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        String outcome = "error";
        try {
            T result = action.get();
            outcome = "ok";
            return result;
        } finally {
            metrics.timer("checkout.provider.latency", "provider", provider, "capability", capability,
                    "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    public void cacheHit(String capability) { metrics.counter("checkout.cache.hits", "capability", capability).increment(); }
    private String valid(String mode) {
        if (!java.util.Set.of("auto", "geoapify-first", "gogoduk-only", "geoapify-only").contains(mode))
            throw new IllegalArgumentException("Invalid checkout provider mode");
        return mode;
    }
    public boolean geoAllowed(String capability) { return !mode(capability).equals("gogoduk-only"); }
    public String modeName(String capability) { return mode(capability); }
    public java.util.List<String> providers(String capability) {
        return switch (mode(capability)) {
            case "geoapify-only" -> java.util.List.of("GEOAPIFY");
            case "gogoduk-only" -> java.util.List.of("GOGODUK");
            case "geoapify-first" -> java.util.List.of("GEOAPIFY", "GOGODUK");
            default -> java.util.List.of("GOGODUK", "GEOAPIFY");
        };
    }
    public boolean allowed(String provider, String capability) {
        boolean allowed = providers(capability).contains(provider) && circuit(provider + ":" + capability).permit(clock.millis());
        if (!allowed) metrics.counter("checkout.provider.skipped", "provider", provider, "capability", capability).increment();
        return allowed;
    }
    public void success(String provider, String capability) { circuit(provider + ":" + capability).success(); }
    public void failure(String provider, String capability, Integer retryAfter, String operation, String reason) {
        circuit(provider + ":" + capability).failure(clock.millis(), retryAfter);
        metrics.counter("checkout.provider.failures", "provider", provider, "capability", capability, "reason", reason).increment();
        LoggerFactory.getLogger(ProviderFallback.class).warn(
                "Checkout provider unavailable: provider={} capability={} operation={} reason={}", provider, capability, operation, reason);
    }
    public void switching(String from, String to, String capability) {
        metrics.counter("checkout.provider.fallback", "from", from, "to", to, "capability", capability).increment();
        LoggerFactory.getLogger(ProviderFallback.class).info(
                "Checkout fallback: from={} to={} capability={}", from, to, capability);
    }
    public boolean primaryAllowed(String capability) {
        return allowed("GOGODUK", capability);
    }
    public void success(String capability) { success("GOGODUK", capability); }
    public void failure(String capability, Integer retryAfter) {
        failure("GOGODUK", capability, retryAfter, "legacy", "unavailable");
    }
    private String mode(String capability) { return capability.equals("routing") ? shippingMode : addressMode; }
    private Circuit circuit(String capability) { return circuits.computeIfAbsent(capability, k -> new Circuit()); }
    private static class Circuit {
        private int failures;
        private long openUntil;
        synchronized boolean permit(long now) {
            if (failures < 3) return true;
            if (now < openUntil) return false;
            openUntil = now + 60000; // only one recovery probe per process
            return true;
        }
        synchronized void success() { failures = 0; openUntil = 0; }
        synchronized void failure(long now, Integer retry) {
            failures++;
            if (retry != null || failures >= 3) {
                failures = Math.max(3, failures);
                openUntil = now + Math.max(60, retry == null ? 60 : retry) * 1000L;
            }
        }
    }
}
