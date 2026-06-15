package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.pipeline.ClickHouseWriter;
import com.gateflow.tracker.pipeline.TrackerKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 事件采集服务
 *
 * <p>持久化路径(结构修正 C):默认 {@code tracker.pipeline.async-kafka=true},事件先写 Kafka
 * (削峰 + 可重放),由 {@link com.gateflow.tracker.pipeline.ClickHouseKafkaConsumer} 批量写
 * ClickHouse;Kafka 不可用时降级为同步写 ClickHouse,再失败才进 DLQ。
 *
 * <p>DLQ 单一所有权:{@link ClickHouseWriter} 只抛异常不再自写 DLQ,DLQ 兜底集中在本类与
 * {@link com.gateflow.tracker.api.EventController} 两条互斥路径,避免对同一事件双写。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventCollectorService {

    private final ClickHouseWriter clickHouseWriter;
    private final TrackerKafkaProducer kafkaProducer;
    private final DLQService dlqService;
    private final TrackerProperties properties;

    /**
     * 采集事件。
     * async-kafka=true:写 Kafka,失败时降级同步写;同步路径异常已在内部兜底 DLQ。
     * async-kafka=false:直接同步写 ClickHouse,异常向上抛由 EventController 兜底。
     */
    public void collect(EventRecord event) {
        if (!properties.getPipeline().isAsyncKafka()) {
            clickHouseWriter.writeBatch(Collections.singletonList(event));
            log.debug("Event collected (sync): eventId={}, type={}", event.getEventId(), event.getEventType());
            return;
        }

        try {
            kafkaProducer.sendEvent(event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Kafka send failed for {}, falling back to synchronous ClickHouse write",
                            event.getEventId(), ex);
                    fallbackWrite(event);
                }
            });
        } catch (Exception e) {
            // send() 同步阶段失败(序列化、缓冲区满、broker 不可达等)
            log.warn("Kafka send threw for {}, falling back to synchronous ClickHouse write",
                    event.getEventId(), e);
            fallbackWrite(event);
        }
        log.debug("Event collected (kafka): eventId={}, type={}", event.getEventId(), event.getEventType());
    }

    /** Kafka 失败时的同步兜底:写 ClickHouse,仍失败则进 DLQ(避免静默丢失)。 */
    private void fallbackWrite(EventRecord event) {
        try {
            clickHouseWriter.writeBatch(Collections.singletonList(event));
        } catch (Exception ce) {
            log.error("Fallback ClickHouse write failed for {}, storing to DLQ", event.getEventId(), ce);
            dlqService.store(event, "clickhouse_write_failed");
        }
    }

    /**
     * 采集事件（仅 Kafka，用于 DLQ 重放）
     */
    public void collectViaKafka(EventRecord event) {
        kafkaProducer.sendEvent(event);
        log.debug("Event sent to Kafka: eventId={}", event.getEventId());
    }
}
