package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.pipeline.ClickHouseWriter;
import com.gateflow.tracker.pipeline.TrackerKafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证持久化路径(结构修正 C)与 DLQ 单一所有权:
 * - async-kafka=true:优先写 Kafka,失败降级同步写,再失败才进 DLQ。
 * - async-kafka=false:直接同步写 ClickHouse。
 */
class EventCollectorServiceTest {

    private final EventRecord event = EventRecord.builder().eventId("evt_1").eventType("click").build();

    private EventCollectorService service(boolean asyncKafka,
                                          ClickHouseWriter writer,
                                          TrackerKafkaProducer producer,
                                          DLQService dlq) {
        TrackerProperties props = new TrackerProperties();
        props.getPipeline().setAsyncKafka(asyncKafka);
        return new EventCollectorService(writer, producer, dlq, props);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, EventRecord>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    void asyncKafkaSuccess_writesToKafkaOnly() {
        ClickHouseWriter writer = mock(ClickHouseWriter.class);
        TrackerKafkaProducer producer = mock(TrackerKafkaProducer.class);
        DLQService dlq = mock(DLQService.class);
        when(producer.sendEvent(any())).thenReturn(ok());

        service(true, writer, producer, dlq).collect(event);

        verify(producer).sendEvent(event);
        verify(writer, never()).writeBatch(anyList());
        verify(dlq, never()).store(any(), any());
    }

    @Test
    void asyncKafkaFailure_fallsBackToSyncWrite() {
        ClickHouseWriter writer = mock(ClickHouseWriter.class);
        TrackerKafkaProducer producer = mock(TrackerKafkaProducer.class);
        DLQService dlq = mock(DLQService.class);
        when(producer.sendEvent(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        service(true, writer, producer, dlq).collect(event);

        verify(writer, times(1)).writeBatch(anyList());
        verify(dlq, never()).store(any(), any());
    }

    @Test
    void asyncKafkaAndSyncBothFail_storesToDlqOnce() {
        ClickHouseWriter writer = mock(ClickHouseWriter.class);
        TrackerKafkaProducer producer = mock(TrackerKafkaProducer.class);
        DLQService dlq = mock(DLQService.class);
        when(producer.sendEvent(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));
        doThrow(new RuntimeException("ch down")).when(writer).writeBatch(anyList());

        service(true, writer, producer, dlq).collect(event);

        verify(dlq, times(1)).store(eq(event), eq("clickhouse_write_failed"));
    }

    @Test
    void syncMode_writesDirectlyToClickHouse() {
        ClickHouseWriter writer = mock(ClickHouseWriter.class);
        TrackerKafkaProducer producer = mock(TrackerKafkaProducer.class);
        DLQService dlq = mock(DLQService.class);

        service(false, writer, producer, dlq).collect(event);

        verify(writer, times(1)).writeBatch(anyList());
        verify(producer, never()).sendEvent(any());
    }
}
