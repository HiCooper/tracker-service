package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 身份解析(Identity Stitching):维护 anonymousId → userId 映射,
 * 把登录前匿名行为与登录后用户缝合,做准用户级分析(漏斗/留存/归因)。
 *
 * <p>映射存于 Redis(tracker:identity:{anonymousId} = userId,带 TTL)。失败一律降级(不影响采集)。
 */
@Slf4j
@Service
public class IdentityService {

    static final String KEY_PREFIX = "tracker:identity:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final boolean enabled;

    public IdentityService(StringRedisTemplate redis, TrackerProperties properties) {
        this.redis = redis;
        TrackerProperties.Identity cfg = properties.getIdentity();
        this.enabled = cfg.isEnabled();
        this.ttl = Duration.ofDays(Math.max(1, cfg.getTtlDays()));
    }

    /** 建立 anonymousId → userId 映射(两者均非空时)。 */
    public void link(String anonymousId, String userId) {
        if (!enabled || !StringUtils.hasText(anonymousId) || !StringUtils.hasText(userId)) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + anonymousId, userId, ttl);
        } catch (Exception e) {
            log.debug("identity link failed for {}: {}", anonymousId, e.getMessage());
        }
    }

    /** 解析 anonymousId 对应的 userId;无映射或失败返回 null。 */
    public String resolve(String anonymousId) {
        if (!enabled || !StringUtils.hasText(anonymousId)) {
            return null;
        }
        try {
            return redis.opsForValue().get(KEY_PREFIX + anonymousId);
        } catch (Exception e) {
            log.debug("identity resolve failed for {}: {}", anonymousId, e.getMessage());
            return null;
        }
    }
}
