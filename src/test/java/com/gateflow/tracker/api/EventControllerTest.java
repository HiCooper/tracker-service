package com.gateflow.tracker.api;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.api.dto.EventRequest;
import com.gateflow.tracker.api.dto.EventResponse;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    @Mock
    private EventCollectorService collectorService;

    @Mock
    private SessionService sessionService;

    @Mock
    private DeduplicationService deduplicationService;

    @Mock
    private EnrichmentService enrichmentService;

    @Mock
    private DLQService dlqService;

    @Mock
    private RateLimiterService rateLimiter;

    @Mock
    private com.gateflow.tracker.metrics.PipelineMetrics metrics;

    @Mock
    private com.gateflow.tracker.validation.SchemaValidationService schemaValidation;

    @Mock
    private PrivacyService privacyService;

    @Mock
    private IdentityService identityService;

    @InjectMocks
    private EventController eventController;

    @Test
    void collect_validEvent_returnsSuccess() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(enrichmentService.enrich(any(EventDTO.class))).thenReturn(createTestEventRecord("evt_001"));
        when(sessionService.getOrCreateSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(Session.builder().sessionId("sess_123").build());
        doNothing().when(collectorService).collect(any(EventRecord.class));

        EventRequest request = createValidRequest("evt_001");
        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
        assertEquals(1, response.getBody().getData().getAccepted());
        assertEquals(0, response.getBody().getData().getDuplicate());
        assertEquals(0, response.getBody().getData().getRejected());
    }

    @Test
    void collect_duplicateEvent_incrementsDuplicateCount() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(deduplicationService.isDuplicate("evt_001")).thenReturn(true);

        EventRequest request = createValidRequest("evt_001");
        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
        assertEquals(0, response.getBody().getData().getAccepted());
        assertEquals(1, response.getBody().getData().getDuplicate());
        assertEquals(0, response.getBody().getData().getRejected());

        verify(collectorService, never()).collect(any());
    }

    @Test
    void collect_invalidEvent_sendsToDLQAndIncrementsRejected() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

        EventRequest request = new EventRequest();
        request.setClientId("test_client");
        EventDTO invalidEvent = new EventDTO();
        request.setEvents(List.of(invalidEvent));

        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getData().getAccepted());
        assertEquals(0, response.getBody().getData().getDuplicate());
        assertEquals(1, response.getBody().getData().getRejected());

        verify(dlqService).store(any(EventRecord.class), eq("validation_failed"));
    }

    @Test
    void collect_rateLimitExceeded_returns429() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);

        EventRequest request = createValidRequest("evt_001");
        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(429, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Rate limit exceeded", response.getBody().getMessage());
    }

    @Test
    void collect_collectionFails_sendsToDLQ() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(enrichmentService.enrich(any(EventDTO.class))).thenReturn(createTestEventRecord("evt_001"));
        when(sessionService.getOrCreateSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(Session.builder().sessionId("sess_123").build());
        doThrow(new RuntimeException("Kafka failed")).when(collectorService).collect(any(EventRecord.class));

        EventRequest request = createValidRequest("evt_001");
        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getData().getAccepted());
        assertEquals(0, response.getBody().getData().getDuplicate());
        assertEquals(1, response.getBody().getData().getRejected());

        verify(dlqService).store(any(EventRecord.class), eq("collection_failed"));
    }

    @Test
    void collect_multipleEvents_mixedResults() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(deduplicationService.isDuplicate("evt_001")).thenReturn(false);
        when(deduplicationService.isDuplicate("evt_002")).thenReturn(true);
        when(deduplicationService.isDuplicate("evt_003")).thenReturn(false);
        when(enrichmentService.enrich(any(EventDTO.class))).thenReturn(createTestEventRecord("evt_001"));
        when(sessionService.getOrCreateSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(Session.builder().sessionId("sess_123").build());
        doNothing().when(collectorService).collect(any(EventRecord.class));

        EventRequest request = new EventRequest();
        request.setClientId("test_client");
        request.setEvents(List.of(
                createEventDTO("evt_001"),
                createEventDTO("evt_002"),
                createEventDTO("evt_003")
        ));

        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().getData().getAccepted());
        assertEquals(1, response.getBody().getData().getDuplicate());
        assertEquals(0, response.getBody().getData().getRejected());
    }

    @Test
    void collect_schemaViolationEnforce_quarantinesAndRejects() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(schemaValidation.check(any(EventDTO.class), anyString()))
                .thenReturn(com.gateflow.tracker.validation.SchemaValidationService.Outcome.VIOLATION_ENFORCE);

        EventRequest request = createValidRequest("evt_001");
        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().getData().getAccepted());
        assertEquals(1, response.getBody().getData().getRejected());
        verify(dlqService).store(any(EventRecord.class), eq("schema_violation"));
        verify(collectorService, never()).collect(any());
    }

    @Test
    void collect_identifyEvent_linksAndSkipsIngestion() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

        EventRequest request = new EventRequest();
        request.setClientId("c");
        EventDTO id = new EventDTO();
        id.setEventId("evt_id");
        id.setEventType("$identify");
        id.setAnonymousId("anon1");
        id.setUserId("user1");
        id.setTimestamp(System.currentTimeMillis());
        request.setEvents(List.of(id));

        ResponseEntity<EventResponse> response = eventController.collect(request);

        assertEquals(1, response.getBody().getData().getAccepted());
        verify(identityService).link("anon1", "user1");
        verify(collectorService, never()).collect(any());
    }

    @Test
    void collect_anonymousEvent_backfillsUserIdFromIdentityMap() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        EventRecord anon = EventRecord.builder().eventId("e1").eventType("page_view")
                .anonymousId("anon1").build(); // userId 为空
        when(enrichmentService.enrich(any(EventDTO.class))).thenReturn(anon);
        when(identityService.resolve("anon1")).thenReturn("user9");
        when(sessionService.getOrCreateSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(Session.builder().sessionId("s").build());
        doNothing().when(collectorService).collect(any(EventRecord.class));

        eventController.collect(createValidRequest("e1"));

        verify(identityService).resolve("anon1");
        verify(identityService, never()).link(any(), any());
        assertEquals("user9", anon.getUserId());
    }

    private EventRequest createValidRequest(String eventId) {
        EventRequest request = new EventRequest();
        request.setClientId("test_client");
        request.setEvents(List.of(createEventDTO(eventId)));
        return request;
    }

    private EventDTO createEventDTO(String eventId) {
        EventDTO event = new EventDTO();
        event.setEventId(eventId);
        event.setEventType("page_view");
        event.setUserId("user_1");
        event.setAnonymousId("anon_1");
        event.setTimestamp(System.currentTimeMillis());
        return event;
    }

    private EventRecord createTestEventRecord(String eventId) {
        return EventRecord.builder()
                .eventId(eventId)
                .eventType("page_view")
                .userId("user_1")
                .anonymousId("anon_1")
                .sessionId("sess_123")
                .timestamp(Instant.now())
                .receivedAt(Instant.now())
                .build();
    }
}