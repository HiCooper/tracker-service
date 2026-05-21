package com.gateflow.tracker.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.DLQEntry;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.pipeline.ClickHouseWriter;
import com.gateflow.tracker.service.DLQService;
import com.gateflow.tracker.service.DeduplicationService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class DLQReplayTask {

    private final DLQService dlqService;
    private final ClickHouseWriter clickHouseWriter;
    private final DeduplicationService deduplicationService;
    private final ObjectMapper objectMapper;
    private final TrackerProperties properties;
    private final CircuitBreaker circuitBreaker;

    public DLQReplayTask(DLQService dlqService,
                         ClickHouseWriter clickHouseWriter,
                         DeduplicationService deduplicationService,
                         ObjectMapper objectMapper,
                         TrackerProperties properties,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.dlqService = dlqService;
        this.clickHouseWriter = clickHouseWriter;
        this.deduplicationService = deduplicationService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("clickhouse");
    }

    @Scheduled(fixedDelayString = "${tracker.dlq.replay-interval-ms:60000}",
               initialDelayString = "${tracker.dlq.replay-initial-delay-ms:30000}")
    public void replay() {
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.info("Circuit breaker is OPEN, skipping DLQ replay");
            return;
        }

        List<DLQEntry> entries = dlqService.fetchForReplay(properties.getDlq().getBatchSize());

        if (entries.isEmpty()) {
            log.debug("No DLQ entries to replay");
            return;
        }

        log.info("Replaying {} DLQ entries", entries.size());

        int success = 0;
        int failed = 0;

        for (DLQEntry entry : entries) {
            try {
                replayEntry(entry);
                dlqService.updateRetryInfo(entry, true);
                success++;
            } catch (Exception e) {
                log.error("Failed to replay entry {}", entry.getEventId(), e);
                dlqService.updateRetryInfo(entry, false);
                failed++;
            }
        }

        log.info("DLQ replay completed: success={}, failed={}", success, failed);
    }

    private void replayEntry(DLQEntry entry) throws Exception {
        EventRecord event = objectMapper.readValue(entry.getEventJson(), EventRecord.class);

        if (deduplicationService.isDuplicate(event.getEventId())) {
            log.info("Event {} already processed, skipping DLQ replay", event.getEventId());
            return;
        }

        clickHouseWriter.writeBatch(Collections.singletonList(event));
        deduplicationService.markProcessed(event.getEventId());

        log.debug("Successfully replayed event {}", event.getEventId());
    }
}
