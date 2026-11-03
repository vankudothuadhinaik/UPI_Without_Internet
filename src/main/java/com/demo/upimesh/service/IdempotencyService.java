package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

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
    public String claim(String packetHash) {
        String claimToken = UUID.randomUUID().toString();
        try {
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(packetHash, claimToken, Duration.ofHours(24));
            return Boolean.TRUE.equals(success) ? claimToken : null;
        } catch (Exception e) {
            if (!localFallbackEnabled) {
                // A process-local cache cannot protect settlement across multiple instances.
                throw new IllegalStateException(
                        "Redis idempotency store unavailable; refusing to claim payment packet", e);
            }
            return localCache.putIfAbsent(packetHash, claimToken) == null
                    ? claimToken
                    : null;
        }
    }

    /**
     * Release only the claim owned by this token. A delayed worker must never
     * delete a newer worker's claim after the original Redis TTL has expired.
     */
    public boolean release(String packetHash, String claimToken) {
        if (packetHash == null || claimToken == null) {
            return false;
        }
        try {
            Long deleted = redisTemplate.execute(
                    RELEASE_IF_OWNER, Collections.singletonList(packetHash), claimToken);
            return deleted != null && deleted > 0;
        } catch (Exception e) {
            boolean releasedLocally = localCache.remove(packetHash, claimToken);
            if (!releasedLocally) {
                log.warn("Could not release Redis idempotency claim for packet {}", packetHash);
            }
            return releasedLocally;
        }
    }

    // Methods needed by ApiController
    public int size() {
        try {
            // Redis doesn't have a simple size for all keys, so we use local cache size as proxy in dev
            // In production, this would use Redis SCAN or INFO keyspace
            return localCache.size();
        } catch (Exception e) {
            return localCache.size();
        }
    }

    public void clear() {
        try {
            // Note: In production with Redis, this would flush the specific prefix, not all keys
            localCache.clear();
        } catch (Exception e) {
            localCache.clear();
        }
    }
}
