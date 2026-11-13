package com.example.fashion_backend.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
public class JdbcFallbackStore implements FallbackStore {
    private final JdbcTemplate jdbc;
    private final AtomicLong cleanupAt = new AtomicLong();
    public JdbcFallbackStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String get(String key) {
        cleanup();
        return jdbc.query("SELECT payload FROM checkout_provider_cache WHERE cache_key=? AND expires_at>clock_timestamp()",
                rs -> rs.next() ? rs.getString(1) : null, key);
    }
    @Override public void put(String key, String value, Duration ttl) {
        jdbc.update("""
            INSERT INTO checkout_provider_cache(cache_key,payload,expires_at)
            VALUES(?,?,clock_timestamp() + (? * interval '1 millisecond'))
            ON CONFLICT(cache_key) DO UPDATE SET payload=excluded.payload,expires_at=excluded.expires_at
            """, key, value, ttl.toMillis());
    }
    @Override public boolean acquire(String bucket, int limit, int windowMillis) {
        // Use database time on every node; only the first limit requests in a window succeed.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            INSERT INTO checkout_provider_limits(bucket,window_id,request_count,expires_at)
            VALUES(?,floor(extract(epoch FROM clock_timestamp())*1000/?)::bigint,1,clock_timestamp()+interval '2 minutes')
            ON CONFLICT(bucket) DO UPDATE SET
              request_count=CASE WHEN checkout_provider_limits.window_id=excluded.window_id
                                THEN least(checkout_provider_limits.request_count+1,1000000) ELSE 1 END,
              window_id=excluded.window_id,expires_at=excluded.expires_at
            RETURNING request_count<=?
            """, Boolean.class, bucket, windowMillis, limit));
    }
    private void cleanup() {
        long now = System.currentTimeMillis(), previous = cleanupAt.get();
        if (now >= previous && cleanupAt.compareAndSet(previous, now + 60000)) {
            jdbc.update("DELETE FROM checkout_provider_cache WHERE expires_at<=clock_timestamp()");
            jdbc.update("DELETE FROM checkout_provider_limits WHERE expires_at<=clock_timestamp()");
        }
    }
}
