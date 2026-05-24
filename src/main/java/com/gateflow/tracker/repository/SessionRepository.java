package com.gateflow.tracker.repository;

import com.gateflow.tracker.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
@Slf4j
public class SessionRepository {

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Session findById(String sessionId) {
        String sql = "SELECT * FROM sessions WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToSession(rs);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find session by id: {}", sessionId, e);
        }
        return null;
    }

    public List<Session> findActiveSessionsSince(Instant since) {
        String sql = "SELECT * FROM sessions WHERE last_active_at >= ? AND end_time IS NULL";
        List<Session> sessions = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Convert Instant to LocalDateTime for ClickHouse compatibility
            java.time.LocalDateTime localDateTime = java.time.LocalDateTime.ofInstant(since, java.time.ZoneOffset.UTC);
            stmt.setObject(1, localDateTime);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    sessions.add(mapResultSetToSession(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find active sessions since: {}", since, e);
        }
        return sessions;
    }

    public Session save(Session session) {
        String sql = """
            INSERT INTO sessions (
                session_id, user_id, anonymous_id, platform,
                start_time, end_time, duration,
                page_views, clicks, exposures, scroll_depth_max,
                is_bounce, bounce_page,
                first_page_url, last_page_url,
                utm_source, utm_medium, utm_campaign,
                device_type, os, last_active_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

        stmt.setObject(idx++, session.getStartTime() != null ? java.time.LocalDateTime.ofInstant(session.getStartTime(), java.time.ZoneOffset.UTC) : null);
        stmt.setObject(idx++, session.getEndTime() != null ? java.time.LocalDateTime.ofInstant(session.getEndTime(), java.time.ZoneOffset.UTC) : null);
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

        stmt.setObject(idx++, session.getLastActiveAt() != null ? java.time.LocalDateTime.ofInstant(session.getLastActiveAt(), java.time.ZoneOffset.UTC) : java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    private Session mapResultSetToSession(ResultSet rs) throws SQLException {
        return Session.builder()
                .sessionId(rs.getString("session_id"))
                .userId(rs.getString("user_id"))
                .anonymousId(rs.getString("anonymous_id"))
                .platform(rs.getString("platform"))
                .startTime(rs.getObject("start_time") != null ? 
                    ((java.time.LocalDateTime) rs.getObject("start_time")).atZone(java.time.ZoneOffset.UTC).toInstant() : null)
                .endTime(rs.getObject("end_time") != null ? 
                    ((java.time.LocalDateTime) rs.getObject("end_time")).atZone(java.time.ZoneOffset.UTC).toInstant() : null)
                .duration(rs.getObject("duration") != null ? rs.getLong("duration") : null)
                .pageViews(rs.getInt("page_views"))
                .clicks(rs.getInt("clicks"))
                .exposures(rs.getInt("exposures"))
                .scrollDepthMax(rs.getInt("scroll_depth_max"))
                .isBounce(rs.getInt("is_bounce") == 1)
                .bouncePage(rs.getString("bounce_page"))
                .firstPageUrl(rs.getString("first_page_url"))
                .lastPageUrl(rs.getString("last_page_url"))
                .utmSource(rs.getString("utm_source"))
                .utmMedium(rs.getString("utm_medium"))
                .utmCampaign(rs.getString("utm_campaign"))
                .deviceType(rs.getString("device_type"))
                .os(rs.getString("os"))
                .lastActiveAt(rs.getObject("last_active_at") != null ? 
                    ((java.time.LocalDateTime) rs.getObject("last_active_at")).atZone(java.time.ZoneOffset.UTC).toInstant() : null)
                .build();
    }
}
