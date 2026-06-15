package com.gateflow.tracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防回归:确认 Redis 配置位于 Boot 3 期望的 {@code spring.data.redis} 命名空间下。
 *
 * <p>若误用 Boot 2 的 {@code spring.redis},注入的 {@link RedisProperties} 会绑定空值,
 * 导致 {@code REDIS_HOST/PORT/PASSWORD} 环境变量被静默忽略——这正是上线前修复的 P0 bug。
 */
class RedisPropertiesBindingTest {

    private Binder binderFromApplicationYml() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return Binder.get(env);
    }

    @Test
    void redisConfigBindsUnderSpringDataRedisNamespace() throws IOException {
        Binder binder = binderFromApplicationYml();

        RedisProperties props = binder.bind("spring.data.redis", RedisProperties.class).get();

        assertThat(props.getHost()).isEqualTo("localhost");
        assertThat(props.getPort()).isEqualTo(6379);
        assertThat(props.getDatabase()).isEqualTo(0);
        assertThat(props.getLettuce().getPool().getMaxActive()).isEqualTo(50);
    }

    @Test
    void legacySpringRedisNamespaceIsNotUsed() throws IOException {
        Binder binder = binderFromApplicationYml();

        // 旧命名空间下不应再有 host,否则说明配置回退到了被忽略的 spring.redis.*
        assertThat(binder.bind("spring.redis.host", String.class).isBound()).isFalse();
    }
}
