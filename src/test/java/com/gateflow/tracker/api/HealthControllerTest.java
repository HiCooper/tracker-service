package com.gateflow.tracker.api;

import com.gateflow.tracker.service.DLQService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final DLQService dlq = mock(DLQService.class);

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

        return new HealthController(ds, dlq, rf);
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
