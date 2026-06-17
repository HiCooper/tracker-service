package com.gateflow.tracker.api;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.api.dto.EventRequest;
import com.gateflow.tracker.api.dto.EventResponse;
import com.gateflow.tracker.metrics.PipelineMetrics;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventCollectorService collectorService;
    private final SessionService sessionService;
    private final DeduplicationService deduplicationService;
    private final EnrichmentService enrichmentService;
    private final DLQService dlqService;
    private final RateLimiterService rateLimiter;
    private final PipelineMetrics metrics;
    private final com.gateflow.tracker.validation.SchemaValidationService schemaValidation;

    @PostMapping("/collect")
    public ResponseEntity<EventResponse> collect(@Valid @RequestBody EventRequest request) {
        // 限流检查
        String clientId = request.getClientId() != null ? request.getClientId() : "default";
        if (!rateLimiter.tryAcquire(clientId)) {
            return ResponseEntity.status(429)
                    .body(EventResponse.error("Rate limit exceeded"));
        }

        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;

        for (EventDTO event : request.getEvents()) {
            // 基础校验
            if (!validateEvent(event)) {
                rejected++;
                metrics.incrementRejected();
                dlqService.store(toEventRecord(event), "validation_failed");
                metrics.incrementDlqStored();
                continue;
            }

            // 事件契约校验:enforce 模式下违规事件进隔离区,不进主表;monitor 模式仅打点
            if (schemaValidation.check(event, clientId)
                    == com.gateflow.tracker.validation.SchemaValidationService.Outcome.VIOLATION_ENFORCE) {
                rejected++;
                metrics.incrementRejected();
                dlqService.store(toEventRecord(event), "schema_violation");
                metrics.incrementDlqStored();
                continue;
            }

            // 去重检查
            if (deduplicationService.isDuplicate(event.getEventId())) {
                duplicate++;
                metrics.incrementDuplicate();
                continue;
            }

            // 数据增强
            EventRecord enriched = enrichmentService.enrich(event);

            // 会话管理
            Session session = sessionService.getOrCreateSession(
                    enriched.getUserId(),
                    enriched.getAnonymousId(),
                    event.getSession() != null ? event.getSession().getSessionId() : null,
                    event.getPage(),
                    event.getContext(),
                    event.getDevice()
            );

            enriched.setSessionId(session.getSessionId());

            // 更新会话聚合指标
            sessionService.updateSessionMetrics(session.getSessionId(), enriched);

            // 采集事件
            try {
                collectorService.collect(enriched);
                accepted++;
                metrics.incrementAccepted();
            } catch (Exception e) {
                log.error("Failed to collect event {}", event.getEventId(), e);
                dlqService.store(enriched, "collection_failed");
                metrics.incrementDlqStored();
                rejected++;
                metrics.incrementRejected();
            }
        }

        return ResponseEntity.ok(EventResponse.success(accepted, duplicate, rejected));
    }

    private boolean validateEvent(EventDTO event) {
        return event != null
                && event.getEventId() != null && !event.getEventId().isEmpty()
                && event.getEventType() != null && !event.getEventType().isEmpty()
                && event.getTimestamp() != null;
    }

    private EventRecord toEventRecord(EventDTO event) {
        return EventRecord.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .userId(event.getUserId())
                .anonymousId(event.getAnonymousId())
                .sessionId(event.getSession() != null ? event.getSession().getSessionId() : null)
                .timestamp(event.getTimestamp() != null ?
                        java.time.Instant.ofEpochMilli(event.getTimestamp()) : java.time.Instant.now())
                .receivedAt(java.time.Instant.now())
                .build();
    }
}
