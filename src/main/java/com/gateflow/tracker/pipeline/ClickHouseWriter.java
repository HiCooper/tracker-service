package com.gateflow.tracker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.model.EventRecord;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@Slf4j
public class ClickHouseWriter {

    private static final String INSERT_SQL =
            "INSERT INTO gateflow_tracker.events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;

    public ClickHouseWriter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("clickhouse");
    }

    /**
     * 批量写入 ClickHouse。失败时抛出异常,由调用方负责 DLQ 兜底(单一所有权,避免双写)。
     */
    public void writeBatch(List<EventRecord> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        try {
            circuitBreaker.executeRunnable(() -> doWriteBatch(events));
        } catch (Exception ex) {
            log.error("Failed to write {} events to ClickHouse", events.size(), ex);
            throw new RuntimeException("ClickHouse write failed", ex);
        }
    }

    private void doWriteBatch(List<EventRecord> events) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            for (EventRecord e : events) {
                setParameters(stmt, e);
                stmt.addBatch();
            }

            stmt.executeBatch();
            log.debug("Successfully wrote {} events to ClickHouse", events.size());

        } catch (SQLException e) {
            throw new RuntimeException("ClickHouse write failed", e);
        }
    }

    private void setParameters(PreparedStatement stmt, EventRecord e) throws SQLException {
        int i = 1;
        stmt.setString(i++, nullToEmpty(e.getEventId()));
        stmt.setString(i++, nullToEmpty(e.getEventType()));
        stmt.setString(i++, nullToEmpty(e.getUserId()));
        stmt.setString(i++, nullToEmpty(e.getAnonymousId()));
        stmt.setString(i++, nullToEmpty(e.getSessionId()));
        // DateTime64(3) 列必须绑定时间对象,而非 epoch-millis 长整型(否则会被误解析为「秒」)。
        // 统一以 UTC LocalDateTime 绑定,需与 ClickHouse 会话时区(UTC,见 ClickHouseConfig)一致。
        stmt.setObject(i++, toUtcDateTime(e.getTimestamp() != null ? e.getTimestamp() : Instant.now()));
        stmt.setObject(i++, toUtcDateTime(e.getClientTime() != null ? e.getClientTime() : e.getTimestamp()));
        stmt.setObject(i++, toUtcDateTime(e.getReceivedAt() != null ? e.getReceivedAt() : Instant.now()));
        stmt.setString(i++, nullToEmpty(e.getPlatform()));
        stmt.setString(i++, nullToEmpty(e.getAppVersion()));
        stmt.setString(i++, nullToEmpty(e.getSdkVersion()));
        stmt.setString(i++, nullToEmpty(e.getPageUrl()));
        stmt.setString(i++, nullToEmpty(e.getPageTitle()));
        stmt.setString(i++, nullToEmpty(e.getPageReferrer()));
        stmt.setString(i++, nullToEmpty(e.getSpma()));
        stmt.setString(i++, nullToEmpty(e.getSpmb()));
        stmt.setString(i++, nullToEmpty(e.getSpmc()));
        stmt.setString(i++, nullToEmpty(e.getSpmd()));
        stmt.setString(i++, nullToEmpty(e.getDeviceType()));
        stmt.setString(i++, nullToEmpty(e.getOs()));
        stmt.setString(i++, nullToEmpty(e.getBrowser()));
        stmt.setInt(i++, e.getScreenWidth() != null ? e.getScreenWidth() : 0);
        stmt.setInt(i++, e.getScreenHeight() != null ? e.getScreenHeight() : 0);
        stmt.setString(i++, nullToEmpty(e.getLanguage()));
        stmt.setString(i++, nullToEmpty(e.getElementId()));
        stmt.setString(i++, nullToEmpty(e.getElementType()));
        stmt.setString(i++, nullToEmpty(e.getElementText()));

        setNullableInt(stmt, i++, e.getClickX());
        setNullableInt(stmt, i++, e.getClickY());
        setNullableInt(stmt, i++, e.getScrollDepth());
        setNullableLong(stmt, i++, e.getStayDuration());

        stmt.setString(i++, nullToEmpty(e.getUtmSource()));
        stmt.setString(i++, nullToEmpty(e.getUtmMedium()));
        stmt.setString(i++, nullToEmpty(e.getUtmCampaign()));
        stmt.setString(i++, nullToEmpty(e.getUtmTerm()));
        stmt.setString(i++, nullToEmpty(e.getUtmContent()));

        stmt.setObject(i++, e.getExpIds() != null ? e.getExpIds().toArray(new String[0]) : new String[0]);
        stmt.setObject(i++, e.getVariants() != null ? e.getVariants().toArray(new String[0]) : new String[0]);

        stmt.setString(i++, serializeProperties(e));
    }

    /** 将 properties Map 序列化为 JSON 字符串(而非 Map.toString(),后者不是合法 JSON)。 */
    private String serializeProperties(EventRecord e) {
        if (e.getProperties() == null || e.getProperties().isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(e.getProperties());
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize properties for event {}, storing empty object", e.getEventId(), ex);
            return "{}";
        }
    }

    /** 把 Instant 转为 UTC LocalDateTime,供 DateTime64(3) 列绑定;null 回退当前时间。 */
    private static LocalDateTime toUtcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant != null ? instant : Instant.now(), ZoneOffset.UTC);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value != null) {
            stmt.setInt(index, value);
        } else {
            stmt.setNull(index, Types.INTEGER);
        }
    }

    private static void setNullableLong(PreparedStatement stmt, int index, Long value) throws SQLException {
        if (value != null) {
            stmt.setLong(index, value);
        } else {
            stmt.setNull(index, Types.BIGINT);
        }
    }
}
