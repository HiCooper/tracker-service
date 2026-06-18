package com.gateflow.tracker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.model.EventRecord;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试:验证 ClickHouseWriter 对 DateTime64(3) 列与 properties 列的绑定正确性
 * (P0:时间戳须绑定时间对象而非 epoch-millis;properties 须为 JSON 而非 Map.toString())。
 */
class ClickHouseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventRecord sampleEvent() {
        return EventRecord.builder()
                .eventId("evt_1")
                .eventType("click")
                .userId("u1")
                .timestamp(Instant.parse("2026-06-15T07:00:00Z"))
                .clientTime(Instant.parse("2026-06-15T06:59:59Z"))
                .receivedAt(Instant.parse("2026-06-15T07:00:01Z"))
                .properties(Map.of("foo", "bar", "n", 42))
                .build();
    }

    private PreparedStatement runWrite(EventRecord event) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(stmt);

        ClickHouseWriter writer = new ClickHouseWriter(ds, objectMapper, CircuitBreakerRegistry.ofDefaults());
        writer.writeBatch(List.of(event));
        return stmt;
    }

    @Test
    void bindsTimestampColumnsAsDateTimeNotEpochLong() throws Exception {
        PreparedStatement stmt = runWrite(sampleEvent());

        ArgumentCaptor<Object> objects = ArgumentCaptor.forClass(Object.class);
        verify(stmt, org.mockito.Mockito.atLeastOnce()).setObject(anyInt(), objects.capture());

        long dateTimeBindings = objects.getAllValues().stream()
                .filter(v -> v instanceof LocalDateTime)
                .count();
        // timestamp / client_time / received_at 三列
        assertThat(dateTimeBindings).isEqualTo(3);

        // timestamp 列绝不能再用 setLong 绑定 epoch-millis
        verify(stmt, never()).setLong(org.mockito.ArgumentMatchers.eq(6), anyLong());
    }

    @Test
    void serializesPropertiesAsJson() throws Exception {
        PreparedStatement stmt = runWrite(sampleEvent());

        ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
        verify(stmt, org.mockito.Mockito.atLeastOnce()).setString(anyInt(), strings.capture());

        String jsonProps = strings.getAllValues().stream()
                .filter(s -> s.startsWith("{") && s.contains("foo"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON properties bound"));

        Map<String, Object> parsed = objectMapper.readValue(
                jsonProps, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(parsed).containsEntry("foo", "bar").containsEntry("n", 42);
    }

    @Test
    void bindsAppCode() throws Exception {
        PreparedStatement stmt = runWrite(sampleEvent().toBuilder().appCode("A_MAIN").build());

        ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
        verify(stmt, org.mockito.Mockito.atLeastOnce()).setString(anyInt(), strings.capture());
        assertThat(strings.getAllValues()).contains("A_MAIN");
    }

    @Test
    void emptyPropertiesBecomeEmptyJsonObject() throws Exception {
        PreparedStatement stmt = runWrite(sampleEvent().toBuilder().properties(null).build());

        ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
        verify(stmt, org.mockito.Mockito.atLeastOnce()).setString(anyInt(), strings.capture());

        assertThat(strings.getAllValues()).contains("{}");
    }
}
