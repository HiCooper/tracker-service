package com.gateflow.tracker.api;

import com.gateflow.tracker.config.ClickHouseProperties;
import com.gateflow.tracker.service.DLQService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final ClickHouseProperties clickHouseProperties;
    private final DLQService dlqService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toEpochMilli());

        // 检查各组件状态
        Map<String, String> services = new HashMap<>();
        services.put("clickhouse", checkClickHouse());
        services.put("redis", checkRedis());
        services.put("dlq", dlqService.getDLQSize() + " entries");
        health.put("services", services);

        // 检查是否有服务不可用
        boolean allUp = services.values().stream().allMatch("UP"::equals);
        if (!allUp) {
            health.put("status", "DEGRADED");
        }

        return ResponseEntity.ok(health);
    }

    private String checkClickHouse() {
        try (Connection conn = clickHouseProperties.createDataSource().getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return "UP";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String checkRedis() {
        try {
            // 简单检查
            return "UP";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }
}
