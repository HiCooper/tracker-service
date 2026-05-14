package com.gateflow.tracker.config;

import com.gateflow.tracker.model.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerConfig {

    private final KafkaProperties kafkaProperties;

    @Bean
    public ConsumerFactory<String, EventRecord> consumerFactory() {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        // 反序列化器
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // 可靠性配置
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");   // 从最早消费
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);       // 手动提交

        // 性能配置
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);    // 每次拉取 100 条
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);    // 最小拉取 1KB
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);    // 最大等待 500ms

        // 幂等性配置
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        JsonDeserializer<EventRecord> deserializer = new JsonDeserializer<>(EventRecord.class);
        deserializer.setRemoveTypeHeaders(false);
        deserializer.addTrustedPackages("*");
        deserializer.setUseTypeMapperForKey(true);

        return new DefaultKafkaConsumerFactory<>(
                config,
                new StringDeserializer(),
                deserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventRecord> kafkaListenerContainerFactory(
            ConsumerFactory<String, EventRecord> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, EventRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);  // 3 个并发消费者
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventRecord> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, EventRecord> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, EventRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.setBatchListener(true);  // 开启批量消费
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
