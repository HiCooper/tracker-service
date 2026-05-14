package com.gateflow.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.DLQEntry;
import com.gateflow.tracker.model.EventRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DLQServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TrackerProperties properties;

    @Mock
    private TrackerProperties.DLQ dlqConfig;

    private DLQService dlqService;

    @BeforeEach
    void setUp() {
        when(properties.getDlq()).thenReturn(dlqConfig);
        when(dlqConfig.getTtlDays()).thenReturn(Duration.ofDays(7));
        when(dlqConfig.getMaxRetryCount()).thenReturn(10);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        dlqService = new DLQService(redisTemplate, objectMapper, properties);
    }

    @Test
    void store_validEvent_savesToRedis() throws Exception {
        EventRecord event = EventRecord.builder()
                .eventId("evt_001")
                .eventType("page_view")
                .userId("user_1")
                .build();

        when(objectMapper.writeValueAsString(any(EventRecord.class))).thenReturn("{}");
        when(objectMapper.writeValueAsString(any(DLQEntry.class))).thenReturn("{}");

        dlqService.store(event, "test_reason");

        // Verify set was called with the DLQ entry
        verify(valueOperations).set(
                eq("dlq:test_reason:evt_001"),
                anyString(),
                eq(Duration.ofDays(7))
        );

        // Verify added to sorted set for ordering
        verify(zSetOperations).add(
                eq("dlq:score"),
                eq("dlq:test_reason:evt_001"),
                anyDouble()
        );
    }

    @Test
    void getDLQSize_noEntries_returnsZero() {
        when(zSetOperations.size("dlq:score")).thenReturn(null);

        assertEquals(0, dlqService.getDLQSize());
    }

    @Test
    void getDLQSize_hasEntries_returnsSize() {
        when(zSetOperations.size("dlq:score")).thenReturn(5L);

        assertEquals(5, dlqService.getDLQSize());
    }

    @Test
    void fetchForReplay_emptySet_returnsEmptyList() {
        when(zSetOperations.rangeByScore(eq("dlq:score"), eq(0.0), anyDouble()))
                .thenReturn(Set.of());

        assertTrue(dlqService.fetchForReplay(10).isEmpty());
    }

    @Test
    void fetchForReplay_entryNotInWindow_skipsEntry() throws Exception {
        DLQEntry entry = DLQEntry.builder()
                .eventId("evt_001")
                .eventType("page_view")
                .retryCount(0)
                .nextRetryAt(Instant.now().plusSeconds(60)) // Not ready for retry yet
                .build();

        when(zSetOperations.rangeByScore(eq("dlq:score"), eq(0.0), anyDouble()))
                .thenReturn(Set.of("dlq:test:evt_001"));
        when(valueOperations.get("dlq:test:evt_001")).thenReturn("{}");
        when(objectMapper.readValue("{}", DLQEntry.class)).thenReturn(entry);

        assertTrue(dlqService.fetchForReplay(10).isEmpty());
    }

    @Test
    void fetchForReplay_exceededMaxRetries_movesToDeadStorage() throws Exception {
        DLQEntry entry = DLQEntry.builder()
                .eventId("evt_001")
                .eventType("page_view")
                .eventJson("{}")
                .retryCount(10) // Already at max
                .nextRetryAt(Instant.now().minusSeconds(60)) // Ready for retry
                .build();

        when(zSetOperations.rangeByScore(eq("dlq:score"), eq(0.0), anyDouble()))
                .thenReturn(Set.of("dlq:test:evt_001"));
        when(valueOperations.get("dlq:test:evt_001")).thenReturn("{}");
        when(objectMapper.readValue(eq("{}"), eq(DLQEntry.class))).thenReturn(entry);
        when(objectMapper.writeValueAsString(any(DLQEntry.class))).thenReturn("{\"eventId\":\"evt_001\"}");

        assertTrue(dlqService.fetchForReplay(10).isEmpty());

        // Verify it was moved to dead storage
        verify(valueOperations).set(
                eq("dlq:dead:evt_001"),
                anyString(),
                eq(Duration.ofDays(30))
        );
    }

    @Test
    void updateRetryInfo_success_removesFromDLQ() throws Exception {
        DLQEntry entry = DLQEntry.builder()
                .eventId("evt_001")
                .reason("test")
                .retryCount(0)
                .build();

        dlqService.updateRetryInfo(entry, true);

        verify(redisTemplate).delete("dlq:test:evt_001");
        verify(zSetOperations).remove("dlq:score", "dlq:test:evt_001");
    }

    @Test
    void updateRetryInfo_failure_incrementsRetryCount() throws Exception {
        DLQEntry entry = DLQEntry.builder()
                .eventId("evt_001")
                .reason("test")
                .retryCount(0)
                .nextRetryAt(Instant.now())
                .build();

        when(objectMapper.writeValueAsString(any(DLQEntry.class))).thenReturn("{}");

        dlqService.updateRetryInfo(entry, false);

        assertEquals(1, entry.getRetryCount());
        assertNotNull(entry.getNextRetryAt());

        // Verify updated in Redis
        verify(valueOperations).set(
                eq("dlq:test:evt_001"),
                anyString(),
                any(Duration.class)
        );
    }
}