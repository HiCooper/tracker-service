package com.gateflow.tracker.config;

import com.gateflow.tracker.model.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerConfig {

    private final KafkaProperties kafkaProperties;

    @Bean
    public ProducerFactory<String, EventRecord> producerFactory() {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));

        // 序列化器
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // 可靠性配置
        config.put(ProducerConfig.ACKS_CONFIG, "all");           // 等待所有副本确认
        config.put(ProducerConfig.RETRIES_CONFIG, 3);           // 重试 3 次
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);  // 开启幂等性

        // 性能配置
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);   // 批量大小 16KB
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);        // 等待 5ms 凑批
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);  // 32MB 缓冲

        // 压缩
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // 重试间隔
        config.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, EventRecord> kafkaTemplate(
            ProducerFactory<String, EventRecord> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
