package com.gateflow.tracker.validation;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.model.AppSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventValidatorTest {

    private final EventValidator validator = new EventValidator();

    private AppSchema schema() {
        AppSchema.EventSchema purchase = new AppSchema.EventSchema(List.of(
                new AppSchema.FieldSpec("orderId", "string", true),
                new AppSchema.FieldSpec("amount", "number", true),
                new AppSchema.FieldSpec("coupon", "string", false)));
        AppSchema.EventSchema pageView = new AppSchema.EventSchema(List.of());
        return new AppSchema("app1", 1, Map.of("purchase", purchase, "page_view", pageView));
    }

    private EventDTO event(String type, Map<String, Object> custom) {
        return EventDTO.builder()
                .eventId("e1").eventType(type).timestamp(1L)
                .data(EventDTO.EventData.builder().custom(custom).build())
                .build();
    }

    @Test
    void validEventHasNoViolations() {
        assertThat(validator.validate(event("purchase", Map.of("orderId", "O1", "amount", 9.9)), schema()))
                .isEmpty();
    }

    @Test
    void unknownEventTypeIsDrift() {
        assertThat(validator.validate(event("ghost", Map.of()), schema()))
                .anyMatch(v -> v.contains("unknown event type"));
    }

    @Test
    void missingRequiredFieldReported() {
        assertThat(validator.validate(event("purchase", Map.of("orderId", "O1")), schema()))
                .anyMatch(v -> v.contains("missing required field: amount"));
    }

    @Test
    void typeMismatchReported() {
        assertThat(validator.validate(event("purchase", Map.of("orderId", "O1", "amount", "NaN")), schema()))
                .anyMatch(v -> v.contains("expected number"));
    }

    @Test
    void optionalFieldAbsentIsFine() {
        assertThat(validator.validate(event("purchase", Map.of("orderId", "O1", "amount", 1)), schema()))
                .isEmpty();
    }

    @Test
    void eventWithNoFieldSpecAlwaysValid() {
        assertThat(validator.validate(event("page_view", null), schema())).isEmpty();
    }

    @Test
    void nullSchemaPasses() {
        assertThat(validator.validate(event("anything", Map.of()), null)).isEmpty();
    }

    @Test
    void nullCustomWithRequiredFieldsViolates() {
        assertThat(validator.validate(event("purchase", null), schema()))
                .anyMatch(v -> v.contains("missing required field"));
    }
}
