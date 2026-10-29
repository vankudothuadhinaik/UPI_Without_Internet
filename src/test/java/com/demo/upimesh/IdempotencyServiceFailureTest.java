package com.demo.upimesh;

import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceFailureTest {

    @SuppressWarnings("unchecked")
    private IdempotencyService serviceWithUnavailableRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        return new IdempotencyService(redis);
    }

    @Test
    void productionModeFailsClosedWhenRedisIsUnavailable() {
        IdempotencyService service = serviceWithUnavailableRedis();
        ReflectionTestUtils.setField(service, "localFallbackEnabled", false);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.claim("packet-hash"));

        assertTrue(error.getMessage().contains("refusing to claim"));
    }

    @Test
    void explicitDevelopmentFallbackStillDeduplicatesWithinOneProcess() {
        IdempotencyService service = serviceWithUnavailableRedis();
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);

        assertTrue(service.claim("packet-hash"));
        assertFalse(service.claim("packet-hash"));
    }
}
