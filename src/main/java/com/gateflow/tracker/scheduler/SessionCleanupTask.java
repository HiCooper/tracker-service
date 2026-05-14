package com.gateflow.tracker.scheduler;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionRepository;
import com.gateflow.tracker.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupTask {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final TrackerProperties properties;

    /**
     * 定期扫描并结束超时会话
     * 默认每 5 分钟执行一次
     */
    @Scheduled(fixedDelayString = "${tracker.session.cleanup-interval-ms:300000}",
               initialDelayString = "${tracker.session.cleanup-interval-ms:60000}")
    public void cleanupExpiredSessions() {
        // 查询最近 35 分钟内有活动的会话（超时时间 30 分钟 + 5 分钟缓冲）
        Instant threshold = Instant.now().minus(java.time.Duration.ofMinutes(35));

        List<Session> potentiallyExpired = sessionRepository.findActiveSessionsSince(threshold);

        if (potentiallyExpired.isEmpty()) {
            log.debug("No sessions to check for expiration");
            return;
        }

        log.info("Checking {} sessions for expiration", potentiallyExpired.size());

        int cleaned = 0;
        for (Session session : potentiallyExpired) {
            if (isExpired(session)) {
                try {
                    sessionService.endSession(session.getSessionId());
                    cleaned++;
                } catch (Exception e) {
                    log.error("Failed to cleanup session {}", session.getSessionId(), e);
                }
            }
        }

        if (cleaned > 0) {
            log.info("Session cleanup completed, cleaned {} expired sessions", cleaned);
        }
    }

    private boolean isExpired(Session session) {
        java.time.Duration timeout = properties.getSession().getTimeoutMinutes();
        Instant lastActive = session.getLastActiveAt();
        if (lastActive == null) {
            lastActive = session.getStartTime();
        }
        return java.time.Duration.between(lastActive, Instant.now()).compareTo(timeout) > 0;
    }
}
