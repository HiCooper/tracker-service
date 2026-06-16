package com.gateflow.tracker.api;

import com.gateflow.tracker.service.DLQService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final DataSource dataSource;
    private final DLQService dlqService;
    private final LettuceConnectionFactory redisConnectionFactory;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toEpochMilli());

        Map<String, String> services = new HashMap<>();
        services.put("clickhouse", checkClickHouse());
        services.put("redis", checkRedis());
        services.put("dlq", dlqService.getDLQSize() + " entries");
        health.put("services", services);

        boolean allUp = services.get("clickhouse").startsWith("UP")
                && services.get("redis").startsWith("UP");
        if (allUp) {
            return ResponseEntity.ok(health);
        }
        // 依赖不可用时返回 503,使负载均衡/编排健康探针能正确摘除实例。
        health.put("status", "DEGRADED");
        return ResponseEntity.status(503).body(health);
    }

    private String checkClickHouse() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return "UP";
        } catch (SQLException e) {
            log.warn("ClickHouse health check failed: {}", e.getMessage());
            return "DOWN: " + e.getMessage();
        }
    }

    private String checkRedis() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            String pong = conn.ping();
            return "PONG".equals(pong) ? "UP" : "DOWN: unexpected response";
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return "DOWN: " + e.getMessage();
        }
    }
}
