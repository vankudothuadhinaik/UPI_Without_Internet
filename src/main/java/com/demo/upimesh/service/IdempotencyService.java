package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "upi:packet:";
    private static final Duration CLAIM_TTL = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>();

    static {
        RELEASE_IF_OWNER.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('del', KEYS[1]) else return 0 end");
        RELEASE_IF_OWNER.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    @Value("${upi.mesh.idempotency.local-fallback-enabled:false}")
    private boolean localFallbackEnabled;
    
    // Fallback for dev profile (when Redis is not available)
    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();

    @Autowired
    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claim a packet hash and return the unique owner token.
     * A null result means another worker already owns the claim.
     */
    public String claimWithToken(String packetHash) {
        String key = keyFor(packetHash);
        String claimToken = UUID.randomUUID().toString();
        try {
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(key, claimToken, CLAIM_TTL);
            return Boolean.TRUE.equals(success) ? claimToken : null;
        } catch (Exception e) {
            if (!localFallbackEnabled) {
                // A process-local cache cannot protect settlement across multiple instances.
                throw new IllegalStateException(
                        "Redis idempotency store unavailable; refusing to claim payment packet", e);
            }
            return localCache.putIfAbsent(key, claimToken) == null ? claimToken : null;
        }
    }

    /** Backwards-compatible claim check for callers that do not need to release a claim. */
    public boolean claim(String packetHash) {
        return claimWithToken(packetHash) != null;
    }

    /**
     * Release only the claim owned by this token. A delayed worker must never
     * delete a newer worker's claim after the original Redis TTL expires.
     */
    public boolean release(String packetHash, String claimToken) {
        if (packetHash == null || claimToken == null) {
            return false;
        }
        String key = keyFor(packetHash);
        try {
            Long deleted = redisTemplate.execute(
                    RELEASE_IF_OWNER, List.of(key), claimToken);
            return (deleted != null && deleted > 0) || localCache.remove(key, claimToken);
        } catch (Exception e) {
            boolean releasedLocally = localCache.remove(key, claimToken);
            if (!releasedLocally) {
                log.warn("Could not release Redis idempotency claim for packet {}", packetHash);
            }
            return releasedLocally;
        }
    }

    // Methods used by the demo reset/dashboard endpoints.
    public int size() {
        int count = localCache.size();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        } catch (Exception e) {
            log.warn("Could not scan Redis idempotency keys for size");
        }
        return count;
    }

    public void clear() {
        localCache.clear();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            List<String> batch = new ArrayList<>(100);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 100) {
                    redisTemplate.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        } catch (Exception e) {
            log.warn("Could not clear namespaced Redis idempotency keys");
        }
    }

    private static String keyFor(String packetHash) {
        return KEY_PREFIX + packetHash;
    }
}
