package com.example.fashion_backend.service;

import java.time.Duration;
import java.util.function.Supplier;

/** One budget for address verification plus routing, including fallback calls. */
public final class CheckoutDeadline {
    private static final ThreadLocal<Long> END = new ThreadLocal<>();
    private CheckoutDeadline() {}
    public static <T> T run(Supplier<T> action) {
        if (END.get() != null) return action.get();
        END.set(System.nanoTime() + Duration.ofSeconds(10).toNanos());
        try { return action.get(); } finally { END.remove(); }
    }
    public static Duration remaining(Duration requested) {
        Long end = END.get();
        if (Thread.currentThread().isInterrupted() || (end != null && end <= System.nanoTime()))
            throw new ShippingException(504, "SHIPPING_TIMEOUT", "Hết thời gian báo phí. Vui lòng thử lại.");
        return end == null ? requested : Duration.ofNanos(Math.min(requested.toNanos(), end - System.nanoTime()));
    }
}
