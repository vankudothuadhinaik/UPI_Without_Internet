package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "upi:packet:";
    private static final Duration CLAIM_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${upi.mesh.idempotency.local-fallback-enabled:false}")
    private boolean localFallbackEnabled;

    // Fallback for dev profile (when Redis is not available).
    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();

    @Autowired
    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claims a packet hash and returns the unique ownership token.
     * A null result means another delivery currently owns the claim.
     */
    public String claimWithToken(String packetHash) {
        String key = keyFor(packetHash);
        String token = UUID.randomUUID().toString();
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, CLAIM_TTL);
            return Boolean.TRUE.equals(success) ? token : null;
        } catch (Exception e) {
            if (!localFallbackEnabled) {
                // A process-local cache cannot protect settlement across multiple instances.
                // Fail closed in production rather than risk duplicate payment processing.
                throw new IllegalStateException(
                        "Redis idempotency store unavailable; refusing to claim payment packet", e);
            }
            return localCache.putIfAbsent(key, token) == null ? token : null;
        }
    }

    /** Backwards-compatible claim check for callers that do not need to release a claim. */
    public boolean claim(String packetHash) {
        return claimWithToken(packetHash) != null;
    }

    /**
     * Release only the claim owned by this caller. The Redis compare-and-delete is
     * atomic, so an expired claim cannot cause a previous owner to delete a newer one.
     */
    public void release(String packetHash, String claimToken) {
        if (claimToken == null) {
            return;
        }
        String key = keyFor(packetHash);
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), claimToken);
        } catch (Exception ignored) {
            // If Redis is unavailable, leave its key to expire naturally. The
            // process-local fallback entry is still safe to remove by token.
        } finally {
            localCache.remove(key, claimToken);
        }
    }

    // Methods used by the demo reset/dashboard endpoints.
    public int size() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            return (keys == null ? 0 : keys.size()) + localCache.size();
        } catch (Exception e) {
            return localCache.size();
        }
    }

    public void clear() {
        localCache.clear();
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
            // A reset request must not flush unrelated Redis keys.
        }
    }

    private static String keyFor(String packetHash) {
        return KEY_PREFIX + packetHash;
    }
}
