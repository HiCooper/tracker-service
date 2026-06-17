package com.gateflow.tracker.scheduler;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.repository.SessionStore;
import com.gateflow.tracker.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupTask {

    private final SessionService sessionService;
    private final SessionStore sessionStore;
    private final TrackerProperties properties;

    /**
     * 定期扫描 Redis 中超时的活跃会话,结束并把终态落 ClickHouse。
     * 默认每 5 分钟执行一次。
     */
    @Scheduled(fixedDelayString = "${tracker.session.cleanup-interval-ms:300000}",
               initialDelayString = "${tracker.session.cleanup-interval-ms:60000}")
    public void cleanupExpiredSessions() {
        Duration timeout = properties.getSession().getTimeoutMinutes();
        Instant threshold = Instant.now().minus(timeout);

        Set<String> expired = sessionStore.findExpired(threshold);
        if (expired.isEmpty()) {
            log.debug("No expired sessions to finalize");
            return;
        }

        log.info("Finalizing {} expired sessions", expired.size());
        int cleaned = 0;
        for (String sessionId : expired) {
            try {
                sessionService.endSession(sessionId);
                cleaned++;
            } catch (Exception e) {
                log.error("Failed to finalize session {}", sessionId, e);
            }
        }
        if (cleaned > 0) {
            log.info("Session cleanup completed, finalized {} sessions", cleaned);
        }
    }
}
