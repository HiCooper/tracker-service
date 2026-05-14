package com.gateflow.tracker.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Properties;

@Data
@Component
@ConfigurationProperties(prefix = "tracker.clickhouse")
public class ClickHouseProperties {
    private String url = "jdbc:clickhouse://localhost:8123/gateflow_tracker";
    private String user = "default";
    private String password = "";

    public ClickHouseDataSource createDataSource() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        if (password != null && !password.isEmpty()) {
            props.setProperty("password", password);
        }
        return new ClickHouseDataSource(url, props);
    }
}