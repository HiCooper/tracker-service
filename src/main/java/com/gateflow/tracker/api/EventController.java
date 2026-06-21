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
    private final PrivacyService privacyService;
    private final IdentityService identityService;

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

            // 身份解析控制事件:$identify 建立 anonymousId→userId 映射,不入主表
            if ("$identify".equals(event.getEventType())) {
                if (hasText(event.getAnonymousId()) && hasText(event.getUserId())) {
                    identityService.link(event.getAnonymousId(), event.getUserId());
                    metrics.incrementIdentityLinked();
                    accepted++;
                    metrics.incrementAccepted();
                } else {
                    rejected++;
                    metrics.incrementRejected();
                }
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

            // 隐私合规:同意门控 + PII 掩码/哈希(在增强前处理原始字段)
            privacyService.apply(event);

            // 数据增强
            EventRecord enriched = enrichmentService.enrich(event);
            // app 维度:统一为采集 clientId(与契约 key 一致),便于按 app 告警/分析
            enriched.setAppCode(clientId);

            // 身份解析:已识别事件建立映射;匿名事件回填 userId(把匿名行为缝合到用户)
            if (hasText(enriched.getUserId()) && hasText(enriched.getAnonymousId())) {
                identityService.link(enriched.getAnonymousId(), enriched.getUserId());
                metrics.incrementIdentityLinked();
            } else if (!hasText(enriched.getUserId()) && hasText(enriched.getAnonymousId())) {
                String resolved = identityService.resolve(enriched.getAnonymousId());
                if (resolved != null) {
                    enriched.setUserId(resolved);
                    metrics.incrementIdentityResolved();
                }
            }

            // 会话管理
            Session session = sessionService.getOrCreateSession(
                    enriched.getUserId(),
                    enriched.getAnonymousId(),
                    clientId,
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
