package com.gateflow.tracker.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.model.AppSchema;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaRegistryTest {

    @SuppressWarnings("unchecked")
    private SchemaRegistry registryReturning(String json) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(SchemaRegistry.KEY_PREFIX + "app1")).thenReturn(json);
        return new SchemaRegistry(redis, new ObjectMapper());
    }

    @Test
    void parsesSchemaFromRedis() {
        String json = "{\"appId\":\"app1\",\"version\":3,\"events\":{" +
                "\"purchase\":{\"fields\":[{\"name\":\"orderId\",\"type\":\"string\",\"required\":true}]}}}";
        Optional<AppSchema> s = registryReturning(json).get("app1");
        assertThat(s).isPresent();
        assertThat(s.get().getVersion()).isEqualTo(3);
        assertThat(s.get().knowsEvent("purchase")).isTrue();
        assertThat(s.get().eventSchema("purchase").getFields().get(0).getName()).isEqualTo("orderId");
    }

    @Test
    void absentKeyReturnsEmpty() {
        assertThat(registryReturning(null).get("app1")).isEmpty();
    }

    @Test
    void malformedJsonReturnsEmpty() {
        assertThat(registryReturning("{not json").get("app1")).isEmpty();
    }

    @Test
    void blankAppKeyReturnsEmpty() {
        assertThat(registryReturning("{}").get("  ")).isEmpty();
    }
}
