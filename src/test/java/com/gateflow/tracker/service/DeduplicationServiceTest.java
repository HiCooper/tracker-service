package com.gateflow.tracker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.gateflow.tracker.config.TrackerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeduplicationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TrackerProperties properties;

    @Mock
    private TrackerProperties.Dedup dedupConfig;

    @Mock
    private Cache<String, Boolean> localCache;

    private DeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        when(properties.getDedup()).thenReturn(dedupConfig);
        when(dedupConfig.getWindowMinutes()).thenReturn(Duration.ofMinutes(5));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        deduplicationService = new DeduplicationService(redisTemplate, localCache, properties);
    }

    @Test
    void isDuplicate_nullEventId_returnsFalse() {
        assertFalse(deduplicationService.isDuplicate(null));
    }

    @Test
    void isDuplicate_emptyEventId_returnsFalse() {
        assertFalse(deduplicationService.isDuplicate(""));
    }

    @Test
    void isDuplicate_foundInLocalCache_returnsTrue() {
        when(localCache.getIfPresent("evt_001")).thenReturn(true);

        assertTrue(deduplicationService.isDuplicate("evt_001"));

        // Should not check Redis when found in local cache
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void isDuplicate_notInCache_newInRedis_returnsFalse() {
        when(localCache.getIfPresent("evt_002")).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertFalse(deduplicationService.isDuplicate("evt_002"));

        // Should add to local cache
        verify(localCache).put("evt_002", true);
    }

    @Test
    void isDuplicate_notInCache_existsInRedis_returnsTrue() {
        when(localCache.getIfPresent("evt_003")).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertTrue(deduplicationService.isDuplicate("evt_003"));

        // Should add to local cache anyway
        verify(localCache).put("evt_003", true);
    }

    @Test
    void isDuplicate_redisException_returnsFalse() {
        when(localCache.getIfPresent("evt_004")).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // Should return false (fail open) to avoid losing events
        assertFalse(deduplicationService.isDuplicate("evt_004"));
    }

    @Test
    void markProcessed_nullEventId_doesNothing() {
        deduplicationService.markProcessed(null);

        verify(localCache, never()).put(anyString(), any());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void markProcessed_validEventId_marksInBothCaches() {
        deduplicationService.markProcessed("evt_005");

        verify(localCache).put("evt_005", true);
        verify(valueOperations).set(anyString(), eq("1"), any(Duration.class));
    }

    @Test
    void getLocalCacheHitRate_returnsStatsHitRate() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = mock(com.github.benmanes.caffeine.cache.stats.CacheStats.class);
        when(stats.hitRate()).thenReturn(0.833);
        when(localCache.stats()).thenReturn(stats);

        double hitRate = deduplicationService.getLocalCacheHitRate();

        assertEquals(0.833, hitRate, 0.001);
    }
}