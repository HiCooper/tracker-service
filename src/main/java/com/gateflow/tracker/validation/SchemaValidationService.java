package com.gateflow.tracker.validation;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.metrics.PipelineMetrics;
import com.gateflow.tracker.model.AppSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 编排事件契约校验:查注册表 → 校验 → 按模式决定结果,并打点违规指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaValidationService {

    public enum Outcome {
        /** 合规、无 schema 或校验关闭 → 正常采集。 */
        PASS,
        /** 违规但 monitor 模式 → 仍采集,仅打点。 */
        VIOLATION_MONITOR,
        /** 违规且 enforce 模式 → 隔离,不进主表。 */
        VIOLATION_ENFORCE
    }

    private final SchemaRegistry registry;
    private final EventValidator validator;
    private final PipelineMetrics metrics;
    private final TrackerProperties properties;

    public Outcome check(EventDTO event, String appKey) {
        TrackerProperties.Schema.Mode mode = properties.getSchema().getMode();
        if (mode == TrackerProperties.Schema.Mode.OFF) {
            return Outcome.PASS;
        }
        Optional<AppSchema> schema = registry.get(appKey);
        if (schema.isEmpty()) {
            return Outcome.PASS; // 该 app 未发布契约 → 直通
        }
        List<String> violations = validator.validate(event, schema.get());
        if (violations.isEmpty()) {
            return Outcome.PASS;
        }
        metrics.incrementSchemaViolation();
        if (log.isDebugEnabled()) {
            log.debug("Schema violation app={} event={} type={}: {}",
                    appKey, event.getEventId(), event.getEventType(), violations);
        }
        return mode == TrackerProperties.Schema.Mode.ENFORCE
                ? Outcome.VIOLATION_ENFORCE : Outcome.VIOLATION_MONITOR;
    }
}
