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
        props.setProperty("socket_timeout", "30000");
        props.setProperty("connection_timeout", "10000");
        props.setProperty("http_connection_provider", "HTTP_URL_CONNECTION");
        // 时区确定性:以 UTC 解释/序列化 DateTime 值,与 ClickHouseWriter 绑定的 UTC LocalDateTime 一致,
        // 避免随服务器/容器时区漂移导致时间戳偏移。
        props.setProperty("use_server_time_zone", "false");
        props.setProperty("use_time_zone", "UTC");
        return new ClickHouseDataSource(properties.getUrl(), props);
    }
}
