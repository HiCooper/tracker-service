package com.gateflow.tracker.service;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private TrackerProperties properties;

    @Mock
    private TrackerProperties.Session sessionConfig;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        when(properties.getSession()).thenReturn(sessionConfig);
        when(sessionConfig.getTimeoutMinutes()).thenReturn(Duration.ofMinutes(30));
        when(sessionConfig.getCacheTtlMinutes()).thenReturn(Duration.ofMinutes(30));

        sessionService = new SessionService(sessionRepository, properties);
    }

    @Test
    void getOrCreateSession_newUser_createsNewSession() throws Exception {
        when(sessionRepository.findById(anyString())).thenReturn(null);
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        EventRecord event = createTestEvent("user_1", "anon_1");

        EventDTO.PageData pageData = EventDTO.PageData.builder().url("http://example.com").build();
        EventDTO.ContextData contextData = EventDTO.ContextData.builder().utmSource("google").build();
        EventDTO.DeviceData deviceData = EventDTO.DeviceData.builder().userAgent("Mozilla/5.0").build();

        Session session = sessionService.getOrCreateSession(
                "user_1", "anon_1", null,
                pageData,
                contextData,
                deviceData
        );

        assertNotNull(session);
        assertEquals("user_1", session.getUserId());
        assertEquals("anon_1", session.getAnonymousId());
        assertNotNull(session.getSessionId());
        assertTrue(session.getSessionId().startsWith("sess_"));

        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void getOrCreateSession_existingSession_returnsExisting() {
        Session existingSession = Session.builder()
                .sessionId("existing_sess_123")
                .userId("user_1")
                .anonymousId("anon_1")
                .lastActiveAt(Instant.now())
                .build();

        when(sessionRepository.findById("existing_sess_123")).thenReturn(existingSession);

        EventRecord event = createTestEvent("user_1", "anon_1");

        Session session = sessionService.getOrCreateSession(
                "user_1", "anon_1", "existing_sess_123",
                null, null, null
        );

        assertEquals("existing_sess_123", session.getSessionId());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void updateSessionMetrics_incrementsPageViews() {
        Session session = Session.builder()
                .sessionId("sess_123")
                .pageViews(0)
                .clicks(0)
                .lastActiveAt(Instant.now())
                .build();

        when(sessionRepository.findById("sess_123")).thenReturn(session);
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        EventRecord event = EventRecord.builder()
                .eventId("evt_001")
                .eventType("page_view")
                .sessionId("sess_123")
                .timestamp(Instant.now())
                .pageUrl("http://example.com/page1")
                .build();

        sessionService.updateSessionMetrics("sess_123", event);

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());

        assertEquals(1, captor.getValue().getPageViews());
        assertEquals(0, captor.getValue().getClicks()); // page_view doesn't increment clicks
    }

    @Test
    void updateSessionMetrics_clickEvent_incrementsClicks() {
        Session session = Session.builder()
                .sessionId("sess_123")
                .pageViews(0)
                .clicks(0)
                .lastActiveAt(Instant.now())
                .build();

        when(sessionRepository.findById("sess_123")).thenReturn(session);
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        EventRecord event = EventRecord.builder()
                .eventId("evt_001")
                .eventType("click")
                .sessionId("sess_123")
                .timestamp(Instant.now())
                .elementId("btn_1")
                .build();

        sessionService.updateSessionMetrics("sess_123", event);

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());

        assertEquals(1, captor.getValue().getClicks());
        assertEquals(0, captor.getValue().getPageViews());
    }

    @Test
    void updateSessionMetrics_scrollEvent_updatesMaxScrollDepth() {
        Session session = Session.builder()
                .sessionId("sess_123")
                .pageViews(0)
                .clicks(0)
                .scrollDepthMax(0)
                .lastActiveAt(Instant.now())
                .build();

        when(sessionRepository.findById("sess_123")).thenReturn(session);
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        EventRecord event = EventRecord.builder()
                .eventId("evt_001")
                .eventType("scroll")
                .sessionId("sess_123")
                .timestamp(Instant.now())
                .scrollDepth(75)
                .build();

        sessionService.updateSessionMetrics("sess_123", event);

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());

        assertEquals(75, captor.getValue().getScrollDepthMax());
    }

    @Test
    void endSession_setsEndTimeAndDuration() {
        Session session = Session.builder()
                .sessionId("sess_123")
                .startTime(Instant.now().minusSeconds(300))
                .duration(null)
                .endTime(null)
                .lastActiveAt(Instant.now())
                .build();

        when(sessionRepository.findById("sess_123")).thenReturn(session);
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        sessionService.endSession("sess_123");

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());

        assertNotNull(captor.getValue().getEndTime());
        assertNotNull(captor.getValue().getDuration());
        assertTrue(captor.getValue().getDuration() > 0);
    }

    private EventRecord createTestEvent(String userId, String anonymousId) {
        return EventRecord.builder()
                .eventId("evt_" + System.currentTimeMillis())
                .eventType("page_view")
                .userId(userId)
                .anonymousId(anonymousId)
                .timestamp(Instant.now())
                .pageUrl("http://example.com")
                .build();
    }
}