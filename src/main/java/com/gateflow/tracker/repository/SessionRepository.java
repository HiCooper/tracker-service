package com.gateflow.tracker.repository;

import com.gateflow.tracker.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Repository
@Slf4j
public class SessionRepository {

    private static final DateTimeFormatter CH_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 将会话终态一次性写入 ClickHouse(ReplacingMergeTree)。活跃态读改写已迁至 Redis,
     * 这里只在会话结束时写一次,不再做读-改-写。
     */
    public Session save(Session session) {
        String sql = """
            INSERT INTO sessions (
                session_id, user_id, anonymous_id, platform,
                start_time, end_time, duration,
                page_views, clicks, exposures, scroll_depth_max,
                is_bounce, bounce_page,
                first_page_url, last_page_url,
                utm_source, utm_medium, utm_campaign,
                device_type, os, last_active_at, app_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setStatementParams(stmt, session);
            stmt.executeUpdate();
            log.debug("Session saved: {}", session.getSessionId());
            return session;
        } catch (SQLException e) {
            log.error("Failed to save session: {}", session.getSessionId(), e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    private void setStatementParams(PreparedStatement stmt, Session session) throws SQLException {
        int idx = 1;
        stmt.setString(idx++, session.getSessionId());
        stmt.setString(idx++, session.getUserId() != null ? session.getUserId() : "");
        stmt.setString(idx++, session.getAnonymousId() != null ? session.getAnonymousId() : "");
        stmt.setString(idx++, session.getPlatform() != null ? session.getPlatform() : "");

        stmt.setObject(idx++, session.getStartTime() != null ?
                java.time.LocalDateTime.ofInstant(session.getStartTime(), ZoneOffset.UTC).format(CH_DATETIME) : null);
        stmt.setObject(idx++, session.getEndTime() != null ?
                java.time.LocalDateTime.ofInstant(session.getEndTime(), ZoneOffset.UTC).format(CH_DATETIME) : null);
        stmt.setLong(idx++, session.getDuration() != null ? session.getDuration() : 0);

        stmt.setInt(idx++, session.getPageViews() != null ? session.getPageViews() : 0);
        stmt.setInt(idx++, session.getClicks() != null ? session.getClicks() : 0);
        stmt.setInt(idx++, session.getExposures() != null ? session.getExposures() : 0);
        stmt.setInt(idx++, session.getScrollDepthMax() != null ? session.getScrollDepthMax() : 0);

        stmt.setInt(idx++, session.getIsBounce() != null ? (session.getIsBounce() ? 1 : 0) : 0);
        stmt.setString(idx++, session.getBouncePage() != null ? session.getBouncePage() : "");

        stmt.setString(idx++, session.getFirstPageUrl() != null ? session.getFirstPageUrl() : "");
        stmt.setString(idx++, session.getLastPageUrl() != null ? session.getLastPageUrl() : "");

        stmt.setString(idx++, session.getUtmSource() != null ? session.getUtmSource() : "");
        stmt.setString(idx++, session.getUtmMedium() != null ? session.getUtmMedium() : "");
        stmt.setString(idx++, session.getUtmCampaign() != null ? session.getUtmCampaign() : "");

        stmt.setString(idx++, session.getDeviceType() != null ? session.getDeviceType() : "");
        stmt.setString(idx++, session.getOs() != null ? session.getOs() : "");

        stmt.setString(idx++, session.getLastActiveAt() != null ?
                java.time.LocalDateTime.ofInstant(session.getLastActiveAt(), ZoneOffset.UTC).format(CH_DATETIME) :
                java.time.LocalDateTime.now(ZoneOffset.UTC).format(CH_DATETIME));

        stmt.setString(idx++, session.getAppCode() != null ? session.getAppCode() : "");
    }
}
