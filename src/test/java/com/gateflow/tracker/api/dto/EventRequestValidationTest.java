package com.gateflow.tracker.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private EventDTO validEvent(int i) {
        return EventDTO.builder()
                .eventId("evt_" + i).eventType("click").timestamp(System.currentTimeMillis())
                .build();
    }

    private List<EventDTO> events(int n) {
        List<EventDTO> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(validEvent(i));
        return list;
    }

    @Test
    void rejectsBatchOverMaxSize() {
        EventRequest req = EventRequest.builder()
                .events(events(EventRequest.MAX_BATCH_SIZE + 1)).clientId("c").build();
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getMessage().contains("max batch size"));
    }

    @Test
    void acceptsBatchAtMaxSize() {
        EventRequest req = EventRequest.builder()
                .events(events(EventRequest.MAX_BATCH_SIZE)).clientId("c").build();
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsEmptyBatch() {
        EventRequest req = EventRequest.builder().events(List.of()).clientId("c").build();
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getMessage().contains("cannot be empty"));
    }
}
