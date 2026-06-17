package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class IdentityServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private IdentityService service(boolean enabled) {
        TrackerProperties props = new TrackerProperties();
        props.getIdentity().setEnabled(enabled);
        props.getIdentity().setTtlDays(90);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        return new IdentityService(redis, props);
    }

    @Test
    void linkStoresMappingWithTtl() {
        service(true).link("anon1", "user1");
        verify(ops).set(eq("tracker:identity:anon1"), eq("user1"), eq(Duration.ofDays(90)));
    }

    @Test
    void linkIgnoresBlanks() {
        service(true).link("anon1", "  ");
        service(true).link(null, "user1");
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void resolveReadsMapping() {
        when(ops.get("tracker:identity:anon1")).thenReturn("user1");
        assertThat(service(true).resolve("anon1")).isEqualTo("user1");
    }

    @Test
    void disabledServiceIsNoOp() {
        IdentityService s = service(false);
        s.link("anon1", "user1");
        assertThat(s.resolve("anon1")).isNull();
        verifyNoInteractions(ops);
    }
}
