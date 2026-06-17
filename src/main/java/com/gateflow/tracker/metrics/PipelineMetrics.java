package com.gateflow.tracker.metrics;

import com.gateflow.tracker.service.DLQService;
import com.gateflow.tracker.service.DeduplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 采集管道的业务指标(Micrometer → /actuator/prometheus)。
 *
 * <p>此前 accept/reject/dup 计数、DLQ 积压、去重命中率仅散落在日志,无法被监控系统采集。
 * 这里统一注册为可抓取指标,补齐生产可观测性。
 */
@Component
public class PipelineMetrics {

    private final Counter accepted;
    private final Counter duplicate;
    private final Counter rejected;
    private final Counter dlqStored;
    private final Counter schemaViolation;
    private final Counter piiMasked;
    private final Counter consentDenied;

    public PipelineMetrics(MeterRegistry registry,
                           DLQService dlqService,
                           DeduplicationService deduplicationService) {
        this.accepted = Counter.builder("tracker.events.accepted")
                .description("成功采集的事件数").register(registry);
        this.duplicate = Counter.builder("tracker.events.duplicate")
                .description("去重命中(丢弃)的事件数").register(registry);
        this.rejected = Counter.builder("tracker.events.rejected")
                .description("校验失败或采集失败被拒的事件数").register(registry);
        this.dlqStored = Counter.builder("tracker.events.dlq.stored")
                .description("写入 DLQ 的事件数").register(registry);
        this.schemaViolation = Counter.builder("tracker.events.schema_violation")
                .description("不符合 app 事件契约的事件数").register(registry);
        this.piiMasked = Counter.builder("tracker.events.pii_masked")
                .description("发生 PII 掩码/哈希的事件数").register(registry);
        this.consentDenied = Counter.builder("tracker.events.consent_denied")
                .description("因未授权而剥离 PII 的事件数").register(registry);

        Gauge.builder("tracker.dlq.size", dlqService, DLQService::getDLQSize)
                .description("DLQ 当前积压条目数").register(registry);
        Gauge.builder("tracker.dedup.local.hit.rate", deduplicationService,
                        DeduplicationService::getLocalCacheHitRate)
                .description("本地去重缓存命中率").register(registry);
    }

    public void incrementAccepted() { accepted.increment(); }
    public void incrementAccepted(int n) { accepted.increment(n); }
    public void incrementDuplicate() { duplicate.increment(); }
    public void incrementRejected() { rejected.increment(); }
    public void incrementDlqStored() { dlqStored.increment(); }
    public void incrementSchemaViolation() { schemaViolation.increment(); }
    public void incrementPiiMasked() { piiMasked.increment(); }
    public void incrementConsentDenied() { consentDenied.increment(); }
}
