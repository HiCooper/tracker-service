package com.gateflow.tracker.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

@Configuration
public class ClickHouseConfig {

    private final ClickHouseProperties properties;

    public ClickHouseConfig(ClickHouseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public DataSource clickHouseDataSource() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", properties.getUser());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            props.setProperty("password", properties.getPassword());
        }
        return new ClickHouseDataSource(properties.getUrl(), props);
    }
}
