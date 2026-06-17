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
    private Schema schema = new Schema();
    private Privacy privacy = new Privacy();
    private Identity identity = new Identity();

    @Data
    public static class Identity {
        /** 是否启用身份解析(anonymousId→userId 缝合)。 */
        private boolean enabled = true;
        /** 映射保留天数。 */
        private int ttlDays = 90;
    }

    @Data
    public static class Privacy {
        /** 拒绝同意(consent=false)的事件是否剥离 PII(默认 false,非破坏)。 */
        private boolean requireConsent = false;
        /** 是否对 userId 做不可逆哈希。 */
        private boolean hashUserId = false;
        /** 自定义属性中需哈希处理的 PII 字段名。 */
        private java.util.List<String> piiFields = new java.util.ArrayList<>();
        /** 是否对自定义属性做邮箱/手机号启发式掩码。 */
        private boolean maskHeuristics = false;
    }

    @Data
    public static class Pipeline {
        /**
         * true: 事件先入 Kafka(削峰/可重放),Kafka 不可用时降级同步写 ClickHouse。
         * false: 直接同步写 ClickHouse。
         */
        private boolean asyncKafka = true;
    }

    @Data
    public static class Schema {
        /** 事件契约校验模式。 */
        private Mode mode = Mode.MONITOR;

        public enum Mode {
            /** 关闭校验。 */
            OFF,
            /** 仅打点违规指标,事件仍接收(灰度/非破坏,默认)。 */
            MONITOR,
            /** 违规事件进隔离区,不进主表。 */
            ENFORCE
        }
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
