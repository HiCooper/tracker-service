package com.gateflow.tracker.pipeline;

import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.service.DeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickHouseKafkaConsumer {

    private final ClickHouseWriter clickHouseWriter;
    private final DeduplicationService deduplicationService;
    private final TrackerKafkaProducer kafkaProducer;

    @KafkaListener(
            topics = "${tracker.kafka.topics.events:tracker-events}",
            groupId = "tracker-clickhouse-writer",
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
            log.debug("Batch wrote {} events to ClickHouse", events.size());
        } catch (Exception e) {
            log.error("Failed to batch write {} events: {}", events.size(), e);
            events.forEach(event -> kafkaProducer.sendToDLQ(event, "clickhouse_write_failed"));
        }
    }
}
