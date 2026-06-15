package com.gateflow.tracker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.gateflow.tracker.model.EventRecord;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClickHouse 写入集成测试(Testcontainers)。
 *
 * <p>默认跳过:仅当环境变量 {@code RUN_CH_IT=true} 且本机可用 Docker 时运行
 * (CI 中开启;本地/受限网络环境无法拉取镜像时自动跳过,保持 {@code mvn test} 绿色)。
 *
 * <p>覆盖此前 0 测试、所有写入 bug 漏网的核心类:验证 DateTime64(3) 时间戳往返一致、
 * properties 落库为合法 JSON。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_CH_IT", matches = "true")
class ClickHouseWriterIT {

    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer("clickhouse/clickhouse-server:24.3");

    private static DataSource dataSource;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", CLICKHOUSE.getUsername());
        props.setProperty("password", CLICKHOUSE.getPassword());
        props.setProperty("use_server_time_zone", "false");
        props.setProperty("use_time_zone", "UTC");
        // 容器 JDBC URL 形如 jdbc:clickhouse://host:port/default,指向 gateflow_tracker 库
        String url = CLICKHOUSE.getJdbcUrl().replaceAll("/[^/]*$", "/gateflow_tracker");
        // 先用默认库建库与表
        Properties bootstrap = new Properties();
        bootstrap.setProperty("user", CLICKHOUSE.getUsername());
        bootstrap.setProperty("password", CLICKHOUSE.getPassword());
        try (Connection conn = new ClickHouseDataSource(CLICKHOUSE.getJdbcUrl(), bootstrap).getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS gateflow_tracker");
            st.execute(eventsTableDdl());
        }
        dataSource = new ClickHouseDataSource(url, props);
    }

    @Test
    void writesEventWithCorrectTimestampAndJsonProperties() throws Exception {
        EventRecord event = EventRecord.builder()
                .eventId("evt_it_1")
                .eventType("click")
                .userId("u1")
                .timestamp(Instant.parse("2026-06-15T07:00:00Z"))
                .clientTime(Instant.parse("2026-06-15T06:59:59Z"))
                .receivedAt(Instant.parse("2026-06-15T07:00:01Z"))
                .properties(Map.of("foo", "bar", "n", 42))
                .build();

        ClickHouseWriter writer = new ClickHouseWriter(dataSource, MAPPER, CircuitBreakerRegistry.ofDefaults());
        writer.writeBatch(List.of(event));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT toString(timestamp) AS ts, properties FROM gateflow_tracker.events " +
                     "WHERE event_id = 'evt_it_1'")) {
            assertThat(rs.next()).isTrue();
            // 时间戳必须精确往返(若仍按 epoch-millis 误绑会得到错误年份)
            assertThat(rs.getString("ts")).isEqualTo("2026-06-15 07:00:00.000");
            Map<String, Object> props = MAPPER.readValue(rs.getString("properties"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            assertThat(props).containsEntry("foo", "bar").containsEntry("n", 42);
        }
    }

    private static String eventsTableDdl() {
        return "CREATE TABLE IF NOT EXISTS gateflow_tracker.events (" +
                "event_id String, event_type String, user_id String, anonymous_id String, session_id String," +
                "timestamp DateTime64(3), client_time DateTime64(3), received_at DateTime64(3) DEFAULT now64(3)," +
                "platform String, app_version String, sdk_version String, page_url String, page_title String," +
                "page_referrer String, spma String, spmb String, spmc String, spmd String, device_type String," +
                "os String, browser String, screen_width UInt32, screen_height UInt32, language String," +
                "element_id String, element_type String, element_text String, click_x Nullable(Int32)," +
                "click_y Nullable(Int32), scroll_depth Nullable(UInt8), stay_duration Nullable(Int64)," +
                "utm_source String, utm_medium String, utm_campaign String, utm_term String, utm_content String," +
                "exp_ids Array(String), variants Array(String), properties String" +
                ") ENGINE = MergeTree() PARTITION BY toYYYYMMDD(timestamp) " +
                "ORDER BY (user_id, timestamp, event_type, session_id)";
    }
}
