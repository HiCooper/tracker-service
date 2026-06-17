package com.gateflow.tracker.service;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionRepository;
import com.gateflow.tracker.repository.SessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话服务(结构修正 A):活跃会话态存于 Redis,计数走原子操作(无 JVM 锁、跨副本正确),
 * 会话超时/结束时一次性把终态落到 ClickHouse {@code sessions}(ReplacingMergeTree 只写一次,
 * 不再做读-改-写)。
 */
@Service
@Slf4j
public class SessionService {

    private final SessionStore sessionStore;
    private final SessionRepository sessionRepository;
    private final TrackerProperties properties;

    public SessionService(SessionStore sessionStore,
                          SessionRepository sessionRepository,
                          TrackerProperties properties) {
        this.sessionStore = sessionStore;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    /** 获取或创建会话。提供的 sessionId 命中且未过期则复用,否则新建(服务端生成 id)。 */
    public Session getOrCreateSession(String userId, String anonymousId,
                                      String sessionId, EventDTO.PageData pageData,
                                      EventDTO.ContextData contextData, EventDTO.DeviceData deviceData) {
        if (sessionId != null) {
            Session existing = sessionStore.find(sessionId);
            if (existing != null && !isExpired(existing)) {
                return existing;
            }
        }

        Instant now = Instant.now();
        Session newSession = Session.builder()
                .sessionId(generateSessionId())
                .userId(userId)
                .anonymousId(anonymousId)
                .platform("web")
                .startTime(now)
                .lastActiveAt(now)
                .pageViews(0)
                .clicks(0)
                .exposures(0)
                .scrollDepthMax(0)
                .isBounce(false)
                .firstPageUrl(pageData != null ? pageData.getUrl() : null)
                .lastPageUrl(pageData != null ? pageData.getUrl() : null)
                .utmSource(contextData != null ? contextData.getUtmSource() : null)
                .utmMedium(contextData != null ? contextData.getUtmMedium() : null)
                .utmCampaign(contextData != null ? contextData.getUtmCampaign() : null)
                .deviceType(deviceData != null ? parseDeviceType(deviceData.getUserAgent()) : null)
                .os(deviceData != null ? parseOS(deviceData.getUserAgent()) : null)
                .build();

        return sessionStore.create(newSession);
    }

    /** 更新会话聚合指标:全部经 Redis 原子操作,无锁、无读-改-写竞争。 */
    public void updateSessionMetrics(String sessionId, EventRecord event) {
        if (sessionId == null || !sessionStore.exists(sessionId)) {
            return;
        }
        String type = event.getEventType();
        if (type == null) {
            return;
        }
        switch (type) {
            case "page_view" -> {
                sessionStore.increment(sessionId, "pageViews", 1);
                sessionStore.touch(sessionId, Instant.now(), event.getPageUrl());
                return;
            }
            case "click" -> sessionStore.increment(sessionId, "clicks", 1);
            case "exposure" -> sessionStore.increment(sessionId, "exposures", 1);
            case "scroll" -> {
                if (event.getScrollDepth() != null) {
                    sessionStore.updateScrollMax(sessionId, event.getScrollDepth());
                }
            }
            default -> { /* 其他事件仅刷新活跃时间 */ }
        }
        sessionStore.touch(sessionId, Instant.now(), null);
    }

    /** 结束会话:读取终态 → 计算时长/跳出 → 一次性写 ClickHouse → 从 Redis 删除。 */
    public void endSession(String sessionId) {
        Session session = sessionStore.find(sessionId);
        if (session == null) {
            return;
        }
        Instant end = Instant.now();
        session.setEndTime(end);
        session.setDuration(session.getStartTime() != null
                ? Duration.between(session.getStartTime(), end).toMillis() : 0L);

        if (session.getPageViews() != null && session.getPageViews() <= 1
                && session.getDuration() != null && session.getDuration() < 10_000) {
            session.setIsBounce(true);
            session.setBouncePage(session.getFirstPageUrl());
        } else {
            session.setIsBounce(false);
        }

        try {
            sessionRepository.save(session);
        } catch (Exception e) {
            log.error("Failed to persist finalized session {}, keeping in Redis for retry", sessionId, e);
            return; // 落库失败保留 Redis 记录,下次扫描重试,避免丢失
        }
        sessionStore.remove(sessionId);
    }

    private boolean isExpired(Session session) {
        Duration timeout = properties.getSession().getTimeoutMinutes();
        Instant lastActive = session.getLastActiveAt() != null
                ? session.getLastActiveAt() : session.getStartTime();
        if (lastActive == null) {
            return true;
        }
        return Duration.between(lastActive, Instant.now()).compareTo(timeout) > 0;
    }

    private String generateSessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String parseDeviceType(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "mobile";
        } else if (ua.contains("tablet") || ua.contains("ipad")) {
            return "tablet";
        }
        return "desktop";
    }

    private String parseOS(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os") || ua.contains("darwin")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        if (ua.contains("android")) return "Android";
        if (ua.contains("ios") || ua.contains("iphone") || ua.contains("ipad")) return "iOS";
        return "unknown";
    }
}
