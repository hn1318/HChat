package com.hchat.server.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hchat.server.config.HChatProperties;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 令牌桶限流（进程内实现）。启用 Redis 后可由 {@link RateLimiter} 的实现替换为共享计数。
 */
@Service
public class RateLimiterService {

    /** 便于后续接 Redis 时替换实现。 */
    public interface RateLimiter {
        boolean tryAcquire(String key, int permitsPerSecond);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens) {
            this.tokens = tokens;
            this.lastRefillNanos = System.nanoTime();
        }
    }

    private final HChatProperties props;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public RateLimiterService(HChatProperties props) {
        this.props = props;
    }

    public boolean isEnabled() {
        return props.getLimits().isEnabled();
    }

    /** 尝试消费一个令牌；容量为 rps * burst。 */
    public boolean tryAcquire(String key, int permitsPerSecond) {
        if (!props.getLimits().isEnabled() || permitsPerSecond <= 0) {
            return true;
        }
        int capacity = Math.max(1, permitsPerSecond * Math.max(1, props.getLimits().getBurst()));
        Bucket bucket = buckets.get(key, k -> new Bucket(capacity));
        synchronized (bucket) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000d;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * permitsPerSecond);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1d) {
                bucket.tokens -= 1d;
                return true;
            }
            return false;
        }
    }

    public int ipRps() {
        return props.getLimits().getIpRps();
    }

    public int userRps() {
        return props.getLimits().getUserRps();
    }

    public int appRps() {
        return props.getLimits().getAppRps();
    }
}
