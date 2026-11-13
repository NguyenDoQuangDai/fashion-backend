package com.example.fashion_backend.service;

import java.time.Duration;

/** Shared, bounded-lifetime state; implementations must make acquire atomic across instances. */
public interface FallbackStore {
    String get(String key);
    void put(String key, String value, Duration ttl);
    boolean acquire(String bucket, int limit, int windowMillis);
}
