package com.gateflow.tracker.repository;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class SessionStoreTest {

    private StringRedisTemplate redis;
    private HashOperations hashOps;
    private ZSetOperations zsetOps;
    private SessionStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        store = new SessionStore(redis, new TrackerProperties());
    }

    @Test
    void createWritesHashRegistersZsetAndSetsTtl() {
        Session s = Session.builder().sessionId("s1").userId("u").startTime(Instant.now())
                .lastActiveAt(Instant.now()).pageViews(0).clicks(0).exposures(0).scrollDepthMax(0).build();

        store.create(s);

        verify(hashOps).putAll(eq("tracker:session:s1"), any(Map.class));
        verify(redis).expire(eq("tracker:session:s1"), any());
        verify(zsetOps).add(eq("tracker:sessions:active"), eq("s1"), anyDouble());
    }

    @Test
    void findReturnsNullWhenHashEmpty() {
        when(hashOps.entries("tracker:session:x")).thenReturn(Map.of());
        assertThat(store.find("x")).isNull();
    }

    @Test
    void findMapsHashToSession() {
        when(hashOps.entries("tracker:session:s1")).thenReturn(Map.of(
                "sessionId", "s1", "userId", "u", "pageViews", "3", "clicks", "2",
                "scrollDepthMax", "55", "lastActiveAt", String.valueOf(Instant.now().toEpochMilli())));
        Session s = store.find("s1");
        assertThat(s.getSessionId()).isEqualTo("s1");
        assertThat(s.getPageViews()).isEqualTo(3);
        assertThat(s.getClicks()).isEqualTo(2);
        assertThat(s.getScrollDepthMax()).isEqualTo(55);
    }

    @Test
    void incrementDelegatesToHashIncrement() {
        store.increment("s1", "clicks", 1);
        verify(hashOps).increment("tracker:session:s1", "clicks", 1L);
    }

    @Test
    void findExpiredQueriesZsetByScore() {
        Instant before = Instant.now();
        when(zsetOps.rangeByScore(eq("tracker:sessions:active"), eq(0d), anyDouble()))
                .thenReturn(Set.of("s1", "s2"));
        assertThat(store.findExpired(before)).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void removeDeletesHashAndZsetEntry() {
        store.remove("s1");
        verify(redis).delete("tracker:session:s1");
        verify(zsetOps).remove("tracker:sessions:active", "s1");
    }
}
