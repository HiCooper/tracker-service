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
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ClickHouseWriter {

    private final ClickHouseProperties clickHouseProperties;
    private final DLQService dlqService;
    private final CircuitBreaker circuitBreaker;
    private DataSource dataSource;

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
            dataSource = clickHouseProperties.createDataSource();
        }
        return dataSource;
    }

    /**
     * 批量写入事件到 ClickHouse（带熔断保护）
     */
    public void writeBatch(List<EventRecord> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("Circuit breaker open, sending {} events to DLQ", events.size());
            events.forEach(e -> dlqService.store(e, "circuit_breaker_open"));
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
        String sql = buildInsertSQL(events);

        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.execute();
            log.debug("Successfully wrote {} events to ClickHouse", events.size());

        } catch (SQLException e) {
            throw new RuntimeException("ClickHouse write failed", e);
        }
    }

    private String buildInsertSQL(List<EventRecord> events) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO gateflow_tracker.events VALUES ");

        String values = events.stream()
                .map(this::toValueString)
                .collect(Collectors.joining(", "));

        sql.append(values);
        return sql.toString();
    }

    private String toValueString(EventRecord e) {
        StringBuilder sb = new StringBuilder();
        sb.append("('");
        sb.append(escape(e.getEventId())).append("', '");
        sb.append(escape(e.getEventType())).append("', '");
        sb.append(escape(e.getUserId())).append("', '");
        sb.append(escape(e.getAnonymousId())).append("', '");
        sb.append(escape(e.getSessionId())).append("', ");
        sb.append(e.getTimestamp() != null ? e.getTimestamp().toEpochMilli() : 0).append(", ");
        sb.append(e.getClientTime() != null ? e.getClientTime().toEpochMilli() : 0).append(", ");
        sb.append(e.getReceivedAt() != null ? e.getReceivedAt().toEpochMilli() : System.currentTimeMillis()).append(", '");
        sb.append(escape(e.getPlatform())).append("', '");
        sb.append(escape(e.getAppVersion())).append("', '");
        sb.append(escape(e.getSdkVersion())).append("', '");
        sb.append(escape(e.getPageUrl())).append("', '");
        sb.append(escape(e.getPageTitle())).append("', '");
        sb.append(escape(e.getPageReferrer())).append("', '");
        sb.append(escape(e.getSpma())).append("', '");
        sb.append(escape(e.getSpmb())).append("', '");
        sb.append(escape(e.getSpmc())).append("', '");
        sb.append(escape(e.getSpmd())).append("', '");
        sb.append(escape(e.getDeviceType())).append("', '");
        sb.append(escape(e.getOs())).append("', '");
        sb.append(escape(e.getBrowser())).append("', ");
        sb.append(e.getScreenWidth() != null ? e.getScreenWidth() : 0).append(", ");
        sb.append(e.getScreenHeight() != null ? e.getScreenHeight() : 0).append(", '");
        sb.append(escape(e.getLanguage())).append("', '");
        sb.append(escape(e.getElementId())).append("', '");
        sb.append(escape(e.getElementType())).append("', '");
        sb.append(escape(e.getElementText())).append("', ");
        sb.append(e.getClickX() != null ? e.getClickX() : "NULL").append(", ");
        sb.append(e.getClickY() != null ? e.getClickY() : "NULL").append(", ");
        sb.append(e.getScrollDepth() != null ? e.getScrollDepth() : "NULL").append(", ");
        sb.append(e.getStayDuration() != null ? e.getStayDuration() : "NULL").append(", '");
        sb.append(escape(e.getUtmSource())).append("', '");
        sb.append(escape(e.getUtmMedium())).append("', '");
        sb.append(escape(e.getUtmCampaign())).append("', '");
        sb.append(escape(e.getUtmTerm())).append("', '");
        sb.append(escape(e.getUtmContent())).append("', '");
        sb.append(e.getExpIds() != null ? e.getExpIds().toString() : "[]").append("', '");
        sb.append(e.getVariants() != null ? e.getVariants().toString() : "[]").append("', '");
        sb.append(escape(e.getProperties() != null ? e.getProperties().toString() : "{}")).append("')");
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
