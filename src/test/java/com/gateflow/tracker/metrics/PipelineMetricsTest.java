package com.gateflow.tracker.metrics;

import com.gateflow.tracker.service.DLQService;
import com.gateflow.tracker.service.DeduplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineMetricsTest {

    @Test
    void countersAndGaugesAreRegisteredAndReadable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DLQService dlq = mock(DLQService.class);
        DeduplicationService dedup = mock(DeduplicationService.class);
        when(dlq.getDLQSize()).thenReturn(7L);
        when(dedup.getLocalCacheHitRate()).thenReturn(0.42);

        PipelineMetrics m = new PipelineMetrics(registry, dlq, dedup);
        m.incrementAccepted();
        m.incrementAccepted(2);
        m.incrementDuplicate();
        m.incrementRejected();
        m.incrementDlqStored();

        assertThat(registry.get("tracker.events.accepted").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("tracker.events.duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("tracker.events.rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("tracker.events.dlq.stored").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("tracker.dlq.size").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("tracker.dedup.local.hit.rate").gauge().value()).isEqualTo(0.42);
    }
}
