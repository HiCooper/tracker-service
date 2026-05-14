package com.gateflow.tracker.service;

import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.pipeline.ClickHouseWriter;
import com.gateflow.tracker.pipeline.TrackerKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 事件采集服务
 * 负责将事件写入 ClickHouse 和 Kafka
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventCollectorService {

    private final ClickHouseWriter clickHouseWriter;
    private final TrackerKafkaProducer kafkaProducer;

    /**
     * 采集事件：直接写入 ClickHouse（同步路径）
     * Kafka 路径由 ClickHouseKafkaConsumer 异步消费
     */
    public void collect(EventRecord event) {
        // 直接写入 ClickHouse（带熔断保护）
        clickHouseWriter.writeBatch(java.util.Collections.singletonList(event));

        // 可选：同时发送到 Kafka（用于实时流处理）
        // kafkaProducer.sendEvent(event);

        log.debug("Event collected: eventId={}, type={}", event.getEventId(), event.getEventType());
    }

    /**
     * 采集事件（仅 Kafka，用于 DLQ 重放）
     */
    public void collectViaKafka(EventRecord event) {
        kafkaProducer.sendEvent(event);
        log.debug("Event sent to Kafka: eventId={}", event.getEventId());
    }
}
