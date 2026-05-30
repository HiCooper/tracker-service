package com.gateflow.tracker.service;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SessionService {

    private final SessionRepository sessionRepository;
    private final TrackerProperties properties;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public SessionService(SessionRepository sessionRepository, TrackerProperties properties) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    /**
     * 获取或创建会话
     */
    public Session getOrCreateSession(String userId, String anonymousId,
                                       String sessionId, EventDTO.PageData pageData,
                                       EventDTO.ContextData contextData, EventDTO.DeviceData deviceData) {
        if (sessionId != null) {
            Session existing = sessionRepository.findById(sessionId);
            if (existing != null && !isExpired(existing)) {
                return existing;
            }
        }

        // 创建新会话
        Session newSession = Session.builder()
                .sessionId(generateSessionId())
                .userId(userId)
                .anonymousId(anonymousId)
                .platform("web")
                .startTime(Instant.now())
                .lastActiveAt(Instant.now())
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

        return sessionRepository.save(newSession);
    }

    /**
     * 更新会话聚合指标（会话级别加锁，防止并发写丢失）
     */
    public void updateSessionMetrics(String sessionId, EventRecord event) {
        if (sessionId == null) {
            return;
        }

        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            doUpdateSessionMetrics(sessionId, event);
        }

        // 如果会话已过期，清理锁
        if (sessionLocks.size() > 10_000) {
            sessionLocks.clear();
            log.info("Session locks cleared to prevent unbounded growth");
        }
    }

    private void doUpdateSessionMetrics(String sessionId, EventRecord event) {
        if (sessionId == null) {
            return;
        }

        Session session = sessionRepository.findById(sessionId);
        if (session == null) {
            log.warn("Session {} not found for metric update", sessionId);
            return;
        }

        // 检查会话是否已过期
        if (isExpired(session)) {
            return;
        }

        // 更新指标
        switch (event.getEventType()) {
            case "page_view":
                session.setPageViews(session.getPageViews() + 1);
                if (event.getPageUrl() != null) {
                    session.setLastPageUrl(event.getPageUrl());
                }
                break;
            case "click":
                session.setClicks(session.getClicks() + 1);
                break;
            case "exposure":
                session.setExposures(session.getExposures() + 1);
                break;
            case "scroll":
                if (event.getScrollDepth() != null &&
                        event.getScrollDepth() > session.getScrollDepthMax()) {
                    session.setScrollDepthMax(event.getScrollDepth());
                }
                break;
        }

        // 更新最后活跃时间
        session.setLastActiveAt(Instant.now());

        sessionRepository.save(session);
    }

    /**
     * 结束会话
     */
    public void endSession(String sessionId) {
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                Session session = sessionRepository.findById(sessionId);
                if (session == null) {
                    return;
                }

                session.setEndTime(Instant.now());
                session.setDuration(Duration.between(session.getStartTime(), session.getEndTime()).toMillis());

                if (session.getPageViews() != null && session.getPageViews() == 1
                        && session.getDuration() != null && session.getDuration() < 10000) {
                    session.setIsBounce(true);
                    session.setBouncePage(session.getFirstPageUrl());
                }

                sessionRepository.save(session);
            } finally {
                sessionLocks.remove(sessionId);
            }
        }
    }

    /**
     * 检查会话是否过期
     */
    private boolean isExpired(Session session) {
        Duration timeout = properties.getSession().getTimeoutMinutes();
        Instant lastActive = session.getLastActiveAt();
        if (lastActive == null) {
            lastActive = session.getStartTime();
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
