package com.gateflow.tracker.integration;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionStore;
import com.gateflow.tracker.service.IdentityService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 依赖能力的端到端集成测试(Testcontainers):身份解析与会话态。
 *
 * <p>默认跳过:仅当 {@code RUN_REDIS_IT=true} 且本机可用 Docker 时运行(CI 中开启)。
 * 用于验证本地受限网络下无法跑的 Redis 原子操作/映射/超时扫描的真实行为。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class RedisIntegrationIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final TrackerProperties PROPS = new TrackerProperties();

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void identityLinkAndResolveRoundTrips() {
        IdentityService identity = new IdentityService(redis, PROPS);
        assertThat(identity.resolve("anonX")).isNull();
        identity.link("anonX", "userX");
        assertThat(identity.resolve("anonX")).isEqualTo("userX");
    }

    @Test
    void sessionStoreAtomicCountersAndExpiryScan() {
        SessionStore store = new SessionStore(redis, PROPS);
        Instant now = Instant.now();
        Session s = Session.builder().sessionId("sit1").userId("u").appCode("A_MAIN").platform("web")
                .startTime(now).lastActiveAt(now)
                .pageViews(0).clicks(0).exposures(0).scrollDepthMax(0).build();
        store.create(s);

        // 原子计数
        store.increment("sit1", "clicks", 1);
        store.increment("sit1", "clicks", 1);
        store.increment("sit1", "pageViews", 1);
        store.updateScrollMax("sit1", 30);
        store.updateScrollMax("sit1", 10); // 不应覆盖更大的值

        Session loaded = store.find("sit1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAppCode()).isEqualTo("A_MAIN");
        assertThat(loaded.getClicks()).isEqualTo(2);
        assertThat(loaded.getPageViews()).isEqualTo(1);
        assertThat(loaded.getScrollDepthMax()).isEqualTo(30);

        // 超时扫描:把 lastActive 设为过去,应被 findExpired 命中
        store.touch("sit1", now.minusSeconds(3600), null);
        Set<String> expired = store.findExpired(now.minusSeconds(60));
        assertThat(expired).contains("sit1");

        store.remove("sit1");
        assertThat(store.find("sit1")).isNull();
        assertThat(store.findExpired(now)).doesNotContain("sit1");
    }
}
