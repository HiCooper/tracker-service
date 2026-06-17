package com.gateflow.tracker.validation;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.config.TrackerProperties.Schema.Mode;
import com.gateflow.tracker.metrics.PipelineMetrics;
import com.gateflow.tracker.model.AppSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SchemaValidationServiceTest {

    private final SchemaRegistry registry = mock(SchemaRegistry.class);
    private final EventValidator validator = mock(EventValidator.class);
    private final PipelineMetrics metrics = mock(PipelineMetrics.class);
    private final TrackerProperties properties = new TrackerProperties();
    private final SchemaValidationService service =
            new SchemaValidationService(registry, validator, metrics, properties);

    private EventDTO event() {
        return EventDTO.builder().eventId("e1").eventType("purchase").timestamp(1L).build();
    }

    private void mode(Mode m) {
        properties.getSchema().setMode(m);
    }

    @Test
    void offModePasses() {
        mode(Mode.OFF);
        assertThat(service.check(event(), "app1")).isEqualTo(SchemaValidationService.Outcome.PASS);
        verifyNoInteractions(registry);
    }

    @Test
    void noSchemaPasses() {
        mode(Mode.MONITOR);
        when(registry.get("app1")).thenReturn(Optional.empty());
        assertThat(service.check(event(), "app1")).isEqualTo(SchemaValidationService.Outcome.PASS);
    }

    @Test
    void compliantEventPasses() {
        mode(Mode.ENFORCE);
        when(registry.get("app1")).thenReturn(Optional.of(new AppSchema()));
        when(validator.validate(any(), any())).thenReturn(List.of());
        assertThat(service.check(event(), "app1")).isEqualTo(SchemaValidationService.Outcome.PASS);
        verify(metrics, never()).incrementSchemaViolation();
    }

    @Test
    void violationInMonitorModeStillAcceptsButCounts() {
        mode(Mode.MONITOR);
        when(registry.get("app1")).thenReturn(Optional.of(new AppSchema()));
        when(validator.validate(any(), any())).thenReturn(List.of("missing required field: amount"));
        assertThat(service.check(event(), "app1"))
                .isEqualTo(SchemaValidationService.Outcome.VIOLATION_MONITOR);
        verify(metrics).incrementSchemaViolation();
    }

    @Test
    void violationInEnforceModeQuarantines() {
        mode(Mode.ENFORCE);
        when(registry.get("app1")).thenReturn(Optional.of(new AppSchema()));
        when(validator.validate(any(), any())).thenReturn(List.of("unknown event type"));
        assertThat(service.check(event(), "app1"))
                .isEqualTo(SchemaValidationService.Outcome.VIOLATION_ENFORCE);
        verify(metrics).incrementSchemaViolation();
    }
}
