package com.gateflow.tracker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 让 ClickHouse 纳入 Spring Boot Actuator 健康聚合(/actuator/health),
 * 使编排平台的就绪/存活探针能感知 ClickHouse 状态。
 */
@Component("clickHouse")
@RequiredArgsConstructor
public class ClickHouseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return Health.up().build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
