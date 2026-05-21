package com.gateflow.tracker.pipeline;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrackerKafkaProducer {

    private final KafkaTemplate<String, EventRecord> kafkaTemplate;
    private final TrackerProperties properties;

    /**
     * 发送事件到 Kafka
     * 使用 userId 作为 key，保证同一用户的事件有序
     */
    public void sendEvent(EventRecord event) {
        String topic = getTopicForEvent(event);
        String key = event.getUserId() != null ? event.getUserId() :
                     event.getAnonymousId() != null ? event.getAnonymousId() : "unknown";
        int partition = PartitionStrategy.calculatePartition(key, properties.getKafka().getPartitions().getEvents());

        CompletableFuture<SendResult<String, EventRecord>> future =
                kafkaTemplate.send(topic, partition, key, event);

        future.whenComplete(
                (result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send event {}: {}", event.getEventId(), ex.getMessage());
                    } else {
                        log.debug("Event {} sent to partition {} offset {}",
                                event.getEventId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                }
        );
    }

    /**
     * 批量发送事件
     */
    public void sendBatch(java.util.List<EventRecord> events) {
        for (EventRecord event : events) {
            sendEvent(event);
        }
    }

    /**
     * 发送失败事件到 DLQ Topic，保留完整事件信息
     */
    public void sendToDLQ(EventRecord event, String reason) {
        try {
            java.util.Map<String, Object> props = event.getProperties() != null ?
                    new java.util.HashMap<>(event.getProperties()) : new java.util.HashMap<>();
            props.put("_dlq_reason", reason);
            props.put("_dlq_original_timestamp", event.getReceivedAt() != null ?
                    event.getReceivedAt().toString() : java.time.Instant.now().toString());

            EventRecord dlqEvent = event.toBuilder()
                    .receivedAt(java.time.Instant.now())
                    .properties(props)
                    .build();

            kafkaTemplate.send(properties.getKafka().getTopics().getEventsDlq(), event.getEventId(), dlqEvent)
                    .whenComplete(
                            (result, ex) -> {
                                if (ex != null) {
                                    log.error("Failed to send event {} to DLQ: {}", event.getEventId(), ex.getMessage());
                                } else {
                                    log.debug("Event {} sent to DLQ topic", event.getEventId());
                                }
                            }
                    );
        } catch (Exception e) {
            log.error("Failed to prepare DLQ event for {}", event.getEventId(), e);
        }
    }

    private String getTopicForEvent(EventRecord event) {
        String eventType = event.getEventType();
        if ("session_start".equals(eventType) || "session_end".equals(eventType)) {
            return properties.getKafka().getTopics().getSessions();
        }
        return properties.getKafka().getTopics().getEvents();
    }
}
