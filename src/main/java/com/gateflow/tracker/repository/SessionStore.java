package com.gateflow.tracker.repository;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活跃会话态的 Redis 存储(结构修正 A)。
 *
 * <p>此前会话态放在 ClickHouse(ReplacingMergeTree)做读-改-写 + JVM 内锁:跨副本不正确、
 * 读到未合并脏行。改为 Redis Hash 承载活跃态,计数走原子 {@code HINCRBY}、scrollMax 走 Lua 比较,
 * 消除并发读改写竞争;用 ZSET 按 lastActive 排序以便超时扫描;会话超时再一次性落 ClickHouse。
 */
@Repository
@Slf4j
public class SessionStore {

    private static final String KEY_PREFIX = "tracker:session:";
    private static final String ACTIVE_ZSET = "tracker:sessions:active";

    /** scrollDepthMax 原子取大:仅当新值更大才写入。 */
    private static final DefaultRedisScript<Void> SCROLL_MAX_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('HGET', KEYS[1], 'scrollDepthMax') " +
            "if (not cur) or (tonumber(ARGV[1]) > tonumber(cur)) then " +
            "  redis.call('HSET', KEYS[1], 'scrollDepthMax', ARGV[1]) end " +
            "return nil", Void.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionStore(StringRedisTemplate redis, TrackerProperties properties) {
        this.redis = redis;
        // TTL = 会话超时 + 5 分钟缓冲,确保超时扫描有机会先落库
        this.ttl = properties.getSession().getTimeoutMinutes().plusMinutes(5);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(key(sessionId)));
    }

    /** 创建活跃会话:写入全部初始字段,登记到活跃 ZSET,设置 TTL。 */
    public Session create(Session s) {
        String k = key(s.getSessionId());
        Map<String, String> fields = new java.util.HashMap<>();
        put(fields, "sessionId", s.getSessionId());
        put(fields, "userId", s.getUserId());
        put(fields, "anonymousId", s.getAnonymousId());
        put(fields, "platform", s.getPlatform());
        put(fields, "startTime", epoch(s.getStartTime()));
        put(fields, "lastActiveAt", epoch(s.getLastActiveAt()));
        fields.put("pageViews", String.valueOf(orZero(s.getPageViews())));
        fields.put("clicks", String.valueOf(orZero(s.getClicks())));
        fields.put("exposures", String.valueOf(orZero(s.getExposures())));
        fields.put("scrollDepthMax", String.valueOf(orZero(s.getScrollDepthMax())));
        put(fields, "firstPageUrl", s.getFirstPageUrl());
        put(fields, "lastPageUrl", s.getLastPageUrl());
        put(fields, "utmSource", s.getUtmSource());
        put(fields, "utmMedium", s.getUtmMedium());
        put(fields, "utmCampaign", s.getUtmCampaign());
        put(fields, "deviceType", s.getDeviceType());
        put(fields, "os", s.getOs());

        redis.opsForHash().putAll(k, fields);
        redis.expire(k, ttl);
        long score = s.getLastActiveAt() != null ? s.getLastActiveAt().toEpochMilli() : System.currentTimeMillis();
        redis.opsForZSet().add(ACTIVE_ZSET, s.getSessionId(), score);
        return s;
    }

    /** 读取活跃会话;不存在返回 null。 */
    public Session find(String sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(key(sessionId));
        if (h == null || h.isEmpty()) {
            return null;
        }
        return Session.builder()
                .sessionId(str(h, "sessionId"))
                .userId(str(h, "userId"))
                .anonymousId(str(h, "anonymousId"))
                .platform(str(h, "platform"))
                .startTime(instant(h, "startTime"))
                .lastActiveAt(instant(h, "lastActiveAt"))
                .pageViews(intval(h, "pageViews"))
                .clicks(intval(h, "clicks"))
                .exposures(intval(h, "exposures"))
                .scrollDepthMax(intval(h, "scrollDepthMax"))
                .firstPageUrl(str(h, "firstPageUrl"))
                .lastPageUrl(str(h, "lastPageUrl"))
                .utmSource(str(h, "utmSource"))
                .utmMedium(str(h, "utmMedium"))
                .utmCampaign(str(h, "utmCampaign"))
                .deviceType(str(h, "deviceType"))
                .os(str(h, "os"))
                .build();
    }

    /** 原子自增计数字段(pageViews/clicks/exposures)。 */
    public void increment(String sessionId, String field, long delta) {
        redis.opsForHash().increment(key(sessionId), field, delta);
    }

    /** 原子更新 scrollDepthMax 取大。 */
    public void updateScrollMax(String sessionId, int value) {
        redis.execute(SCROLL_MAX_SCRIPT, Collections.singletonList(key(sessionId)), String.valueOf(value));
    }

    /** 刷新最后活跃时间/最后页面 URL,并续期。 */
    public void touch(String sessionId, Instant lastActive, String lastPageUrl) {
        String k = key(sessionId);
        if (lastActive != null) {
            redis.opsForHash().put(k, "lastActiveAt", epoch(lastActive));
            redis.opsForZSet().add(ACTIVE_ZSET, sessionId, lastActive.toEpochMilli());
        }
        if (lastPageUrl != null) {
            redis.opsForHash().put(k, "lastPageUrl", lastPageUrl);
        }
        redis.expire(k, ttl);
    }

    /** 返回 lastActive 早于 before 的会话 ID(超时候选)。 */
    public Set<String> findExpired(Instant before) {
        Set<String> ids = redis.opsForZSet().rangeByScore(ACTIVE_ZSET, 0, before.toEpochMilli());
        return ids != null ? ids : Collections.emptySet();
    }

    /** 删除会话(终态已落库后调用)。 */
    public void remove(String sessionId) {
        redis.delete(key(sessionId));
        redis.opsForZSet().remove(ACTIVE_ZSET, sessionId);
    }

    public long activeCount() {
        Long n = redis.opsForZSet().zCard(ACTIVE_ZSET);
        return n != null ? n : 0;
    }

    // ── helpers ──
    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }
    private static int orZero(Integer v) { return v != null ? v : 0; }
    private static String epoch(Instant i) { return i != null ? String.valueOf(i.toEpochMilli()) : null; }
    private static String str(Map<Object, Object> h, String k) {
        Object v = h.get(k);
        return v != null ? v.toString() : null;
    }
    private static Integer intval(Map<Object, Object> h, String k) {
        Object v = h.get(k);
        return v != null ? Integer.parseInt(v.toString()) : 0;
    }
    private static Instant instant(Map<Object, Object> h, String k) {
        Object v = h.get(k);
        return v != null ? Instant.ofEpochMilli(Long.parseLong(v.toString())) : null;
    }
}
