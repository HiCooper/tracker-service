package com.gateflow.tracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tracker.clickhouse")
public class ClickHouseProperties {
    private String url = "jdbc:clickhouse://localhost:8123/gateflow_tracker";
    private String user = "victor";
    private String password = "victor123";
}