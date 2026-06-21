package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.EventRecord;
import com.gateflow.tracker.model.Session;
import com.gateflow.tracker.repository.SessionRepository;
import com.gateflow.tracker.repository.SessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SessionServiceTest {

    private final SessionStore store = mock(SessionStore.class);
    private final SessionRepository repository = mock(SessionRepository.class);
    private final TrackerProperties properties = new TrackerProperties();
    private final SessionService service = new SessionService(store, repository, properties);

    private EventRecord event(String type) {
        return EventRecord.builder().eventType(type).build();
    }

    @Test
    void reusesExistingNonExpiredSession() {
        Session existing = Session.builder().sessionId("s1").lastActiveAt(Instant.now()).build();
        when(store.find("s1")).thenReturn(existing);

        Session result = service.getOrCreateSession("u", "a", "app1", "s1", null, null, null);

        assertThat(result.getSessionId()).isEqualTo("s1");
        verify(store, never()).create(any());
    }

    @Test
    void createsNewSessionWhenAbsent() {
        when(store.find("old")).thenReturn(null);
        when(store.create(any())).thenAnswer(inv -> inv.getArgument(0));

        Session result = service.getOrCreateSession("u", "a", "app1", "old", null, null, null);

        assertThat(result.getSessionId()).startsWith("sess_");
        assertThat(result.getAppCode()).isEqualTo("app1");
        verify(store).create(any());
    }

    @Test
    void createsNewSessionWhenExistingExpired() {
        Session expired = Session.builder().sessionId("s1")
                .lastActiveAt(Instant.now().minusSeconds(3600)).build(); // > 30min
        when(store.find("s1")).thenReturn(expired);
        when(store.create(any())).thenAnswer(inv -> inv.getArgument(0));

        Session result = service.getOrCreateSession("u", "a", "app1", "s1", null, null, null);

        assertThat(result.getSessionId()).isNotEqualTo("s1");
        verify(store).create(any());
    }

    @Test
    void pageViewIncrementsCounterAndTouchesWithUrl() {
        when(store.exists("s1")).thenReturn(true);
        EventRecord e = EventRecord.builder().eventType("page_view").pageUrl("/p").build();

        service.updateSessionMetrics("s1", e);

        verify(store).increment("s1", "pageViews", 1);
        verify(store).touch(eq("s1"), any(Instant.class), eq("/p"));
    }

    @Test
    void clickIncrementsClicks() {
        when(store.exists("s1")).thenReturn(true);
        service.updateSessionMetrics("s1", event("click"));
        verify(store).increment("s1", "clicks", 1);
    }

    @Test
    void scrollUpdatesMax() {
        when(store.exists("s1")).thenReturn(true);
        service.updateSessionMetrics("s1", EventRecord.builder().eventType("scroll").scrollDepth(80).build());
        verify(store).updateScrollMax("s1", 80);
    }

    @Test
    void skipsMetricsWhenSessionMissing() {
        when(store.exists("s1")).thenReturn(false);
        service.updateSessionMetrics("s1", event("click"));
        verify(store, never()).increment(any(), any(), anyLong());
    }

    @Test
    void endSessionPersistsToClickHouseAndRemovesFromRedis() {
        Session s = Session.builder().sessionId("s1")
                .startTime(Instant.now().minusSeconds(120)).pageViews(3).build();
        when(store.find("s1")).thenReturn(s);

        service.endSession("s1");

        verify(repository).save(any(Session.class));
        verify(store).remove("s1");
    }

    @Test
    void endSessionMarksBounceForSinglePageShortVisit() {
        Session s = Session.builder().sessionId("s1")
                .startTime(Instant.now().minusSeconds(2)).pageViews(1).firstPageUrl("/home").build();
        when(store.find("s1")).thenReturn(s);

        service.endSession("s1");

        verify(repository).save(argThat(saved ->
                Boolean.TRUE.equals(saved.getIsBounce()) && "/home".equals(saved.getBouncePage())));
    }

    @Test
    void endSessionKeepsRedisWhenPersistFails() {
        Session s = Session.builder().sessionId("s1").startTime(Instant.now().minusSeconds(60)).pageViews(2).build();
        when(store.find("s1")).thenReturn(s);
        doThrow(new RuntimeException("ch down")).when(repository).save(any());

        service.endSession("s1");

        verify(store, never()).remove("s1"); // 落库失败保留,等待重试
    }

    @Test
    void endSessionNoOpWhenMissing() {
        when(store.find("ghost")).thenReturn(null);
        service.endSession("ghost");
        verify(repository, never()).save(any());
    }
}
