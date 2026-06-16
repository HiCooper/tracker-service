package com.gateflow.tracker.api;

import com.gateflow.tracker.service.DLQService;
import com.gateflow.tracker.service.DeduplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final DLQService dlq = mock(DLQService.class);
    private final DeduplicationService dedup = mock(DeduplicationService.class);

    private HealthController controller(boolean chUp, boolean redisUp) throws Exception {
        DataSource ds = mock(DataSource.class);
        if (chUp) {
            Connection conn = mock(Connection.class);
            Statement st = mock(Statement.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(st);
        } else {
            when(ds.getConnection()).thenThrow(new SQLException("ch down"));
        }

        LettuceConnectionFactory rf = mock(LettuceConnectionFactory.class);
        RedisConnection rc = mock(RedisConnection.class);
        when(rf.getConnection()).thenReturn(rc);
        when(rc.ping()).thenReturn(redisUp ? "PONG" : "NOPE");

        lenient().when(dlq.getDLQSize()).thenReturn(5L);
        lenient().when(dedup.getLocalCacheHitRate()).thenReturn(0.8);

        return new HealthController(ds, dlq, dedup, rf);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesStructuredPipelineMetrics() throws Exception {
        Map<String, Object> body = controller(true, true).health().getBody();
        Map<String, Object> pipeline = (Map<String, Object>) body.get("pipeline");
        assertThat(pipeline).containsEntry("dlqSize", 5L);
        assertThat(((Number) pipeline.get("dedupHitRate")).doubleValue()).isEqualTo(0.8);
    }

    @Test
    void returns200WhenAllDependenciesUp() throws Exception {
        assertThat(controller(true, true).health().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void returns503WhenClickHouseDown() throws Exception {
        assertThat(controller(false, true).health().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void returns503WhenRedisDown() throws Exception {
        assertThat(controller(true, false).health().getStatusCode().value()).isEqualTo(503);
    }
}
