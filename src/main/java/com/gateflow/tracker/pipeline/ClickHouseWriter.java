package com.gateflow.tracker.pipeline;

import com.gateflow.tracker.config.ClickHouseProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.service.DLQService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

@Component
@Slf4j
public class ClickHouseWriter {

    private static final String INSERT_SQL =
            "INSERT INTO gateflow_tracker.events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final ClickHouseProperties clickHouseProperties;
    private final DLQService dlqService;
    private final CircuitBreaker circuitBreaker;
    private volatile DataSource dataSource;

    public ClickHouseWriter(
            ClickHouseProperties clickHouseProperties,
            DLQService dlqService,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.clickHouseProperties = clickHouseProperties;
        this.dlqService = dlqService;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("clickhouse");
    }

    private DataSource getDataSource() throws SQLException {
        if (dataSource == null) {
            synchronized (this) {
                if (dataSource == null) {
                    dataSource = clickHouseProperties.createDataSource();
                }
            }
        }
        return dataSource;
    }

    public void writeBatch(List<EventRecord> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        try {
            circuitBreaker.executeRunnable(() -> doWriteBatch(events));
        } catch (Exception ex) {
            log.error("Failed to write {} events to ClickHouse", events.size(), ex);
            events.forEach(e -> dlqService.store(e, "clickhouse_write_failed"));
            throw new RuntimeException("ClickHouse write failed", ex);
        }
    }

    private void doWriteBatch(List<EventRecord> events) {
        try (Connection conn = getDataSource().getConnection();
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
        stmt.setLong(i++, e.getTimestamp() != null ? e.getTimestamp().toEpochMilli() : 0);
        stmt.setLong(i++, e.getClientTime() != null ? e.getClientTime().toEpochMilli() : 0);
        stmt.setLong(i++, e.getReceivedAt() != null ? e.getReceivedAt().toEpochMilli() : System.currentTimeMillis());
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

        Object props = e.getProperties();
        stmt.setString(i++, props != null ? props.toString() : "{}");
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
