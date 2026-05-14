package com.gateflow.tracker.pipeline;

import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.service.DeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickHouseKafkaConsumer {

    private final ClickHouseWriter clickHouseWriter;
    private final DeduplicationService deduplicationService;
    private final TrackerKafkaProducer kafkaProducer;

    /**
     * 消费主事件 Topic 并写入 ClickHouse
     */
    @KafkaListener(
            topics = "${tracker.kafka.topics.events:tracker-events}",
            groupId = "tracker-clickhouse-writer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, EventRecord> record) {
        EventRecord event = record.value();

        try {
            // 1. 幂等性检查（防止重复消费）
            if (deduplicationService.isDuplicate(event.getEventId())) {
                log.debug("Event {} already processed, skipping", event.getEventId());
                return;
            }

            // 2. 写入 ClickHouse
            clickHouseWriter.writeBatch(Collections.singletonList(event));

            // 3. 标记已处理
            deduplicationService.markProcessed(event.getEventId());

            log.debug("Consumed and wrote event {}", event.getEventId());
        } catch (Exception e) {
            log.error("Failed to process event {}: {}", event.getEventId(), e);
            // 发送失败事件到 DLQ
            kafkaProducer.sendToDLQ(event, "clickhouse_write_failed");
        }
    }

    /**
     * 批量消费（提升吞吐量）
     */
    @KafkaListener(
            topics = "${tracker.kafka.topics.events:tracker-events}",
            groupId = "tracker-clickhouse-batch-writer",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void consumeBatch(List<ConsumerRecord<String, EventRecord>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        List<EventRecord> events = records.stream()
                .map(ConsumerRecord::value)
                .filter(event -> !deduplicationService.isDuplicate(event.getEventId()))
                .toList();

        if (events.isEmpty()) {
            return;
        }

        try {
            clickHouseWriter.writeBatch(events);
            events.forEach(e -> deduplicationService.markProcessed(e.getEventId()));
            log.info("Batch wrote {} events to ClickHouse", events.size());
        } catch (Exception e) {
            log.error("Failed to batch write {} events: {}", events.size(), e);
            // 逐个发送到 DLQ
            events.forEach(event -> kafkaProducer.sendToDLQ(event, "batch_write_failed"));
        }
    }
}
