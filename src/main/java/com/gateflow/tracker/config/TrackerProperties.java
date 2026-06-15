package com.gateflow.tracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "tracker")
public class TrackerProperties {

    private Session session = new Session();
    private Dedup dedup = new Dedup();
    private DLQ dlq = new DLQ();
    private Kafka kafka = new Kafka();
    private RateLimit rateLimit = new RateLimit();
    private Pipeline pipeline = new Pipeline();

    @Data
    public static class Pipeline {
        /**
         * true: 事件先入 Kafka(削峰/可重放),Kafka 不可用时降级同步写 ClickHouse。
         * false: 直接同步写 ClickHouse。
         */
        private boolean asyncKafka = true;
    }

    @Data
    public static class Session {
        private Duration timeoutMinutes = Duration.ofMinutes(30);
        private Duration cleanupIntervalMs = Duration.ofMinutes(5);
        private Duration heartbeatIntervalMs = Duration.ofSeconds(30);
        private Duration cacheTtlMinutes = Duration.ofMinutes(30);
    }

    @Data
    public static class Dedup {
        private Duration windowMinutes = Duration.ofMinutes(5);
        private boolean twoStageEnabled = true;
        private int localCacheSize = 100_000;
        private Duration localCacheTtlSeconds = Duration.ofSeconds(60);
    }

    @Data
    public static class DLQ {
        private Duration replayIntervalMs = Duration.ofSeconds(60);
        private Duration replayInitialDelayMs = Duration.ofSeconds(30);
        private int batchSize = 100;
        private int maxRetryCount = 10;
        private Duration ttlDays = Duration.ofDays(7);
    }

    @Data
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private Topics topics = new Topics();
        private Partitions partitions = new Partitions();
        private Consumer consumer = new Consumer();

        @Data
        public static class Topics {
            private String events = "tracker-events";
            private String eventsDlq = "tracker-events-dlq";
            private String sessions = "tracker-sessions";
        }

        @Data
        public static class Partitions {
            private int events = 12;
            private int sessions = 6;
        }

        @Data
        public static class Consumer {
            private int concurrency = 3;
            private boolean batchEnabled = true;
        }
    }

    @Data
    public static class RateLimit {
        private int maxPerSecond = 10_000;
        private int burst = 20_000;
    }
}
