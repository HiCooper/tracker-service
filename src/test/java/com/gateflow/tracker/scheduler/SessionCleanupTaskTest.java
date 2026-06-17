package com.gateflow.tracker.scheduler;

import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.repository.SessionStore;
import com.gateflow.tracker.service.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionCleanupTaskTest {

    private final SessionService sessionService = mock(SessionService.class);
    private final SessionStore store = mock(SessionStore.class);
    private final SessionCleanupTask task =
            new SessionCleanupTask(sessionService, store, new TrackerProperties());

    @Test
    void finalizesEachExpiredSession() {
        when(store.findExpired(any(Instant.class))).thenReturn(Set.of("s1", "s2"));
        task.cleanupExpiredSessions();
        verify(sessionService).endSession("s1");
        verify(sessionService).endSession("s2");
    }

    @Test
    void noOpWhenNoExpiredSessions() {
        when(store.findExpired(any(Instant.class))).thenReturn(Set.of());
        task.cleanupExpiredSessions();
        verify(sessionService, never()).endSession(any());
    }

    @Test
    void oneFailureDoesNotStopOthers() {
        when(store.findExpired(any(Instant.class))).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("s1", "s2")));
        doThrow(new RuntimeException("boom")).when(sessionService).endSession("s1");
        task.cleanupExpiredSessions();
        verify(sessionService).endSession("s2");
    }
}
