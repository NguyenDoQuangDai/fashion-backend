package com.example.fashion_backend.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryFallbackStore implements FallbackStore {
    private record Value(String payload, long expires) {}
    private final ConcurrentHashMap<String,Value> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,long[]> limits = new ConcurrentHashMap<>();
    public long now = System.currentTimeMillis();
    @Override public String get(String key) {
        Value value = values.get(key); return value == null || now >= value.expires() ? null : value.payload();
    }
    @Override public void put(String key, String value, Duration ttl) { values.put(key,new Value(value,now+ttl.toMillis())); }
    @Override public synchronized boolean acquire(String bucket,int limit,int period) {
        long window = now/period;
        long[] value = limits.computeIfAbsent(bucket,k -> new long[]{window,0});
        if (value[0] != window) { value[0]=window; value[1]=0; }
        return ++value[1]<=limit;
    }
}
