package com.gateflow.tracker.service;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.metrics.PipelineMetrics;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrivacyServiceTest {

    private final TrackerProperties properties = new TrackerProperties();
    private final PipelineMetrics metrics = mock(PipelineMetrics.class);
    private final PrivacyService service = new PrivacyService(properties, metrics);

    private EventDTO event(String userId, Boolean consent, Map<String, Object> custom) {
        return EventDTO.builder().eventId("e1").eventType("click").timestamp(1L)
                .userId(userId).consent(consent)
                .data(EventDTO.EventData.builder().custom(custom).build())
                .build();
    }

    // ── 纯函数掩码 ──
    @Test
    void hashIsDeterministicIrreversiblePrefixed() {
        assertThat(PrivacyService.hash("u123")).startsWith("h:").isEqualTo(PrivacyService.hash("u123"));
        assertThat(PrivacyService.hash("u123")).isNotEqualTo("u123");
    }

    @Test
    void maskEmailKeepsDomain() {
        assertThat(PrivacyService.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void maskPhoneKeepsLast4() {
        assertThat(PrivacyService.maskPhone("+1 650-555-1234")).isEqualTo("***1234");
    }

    // ── apply ──
    @Test
    void defaultConfigDoesNothing() {
        Map<String, Object> custom = new HashMap<>(Map.of("email", "a@b.com"));
        EventDTO e = event("u1", null, custom);
        service.apply(e);
        assertThat(e.getUserId()).isEqualTo("u1");
        assertThat(custom).containsEntry("email", "a@b.com");
        verifyNoInteractions(metrics);
    }

    @Test
    void consentDeniedStripsUserIdAndPiiFields() {
        properties.getPrivacy().setRequireConsent(true);
        properties.getPrivacy().setPiiFields(List.of("email"));
        Map<String, Object> custom = new HashMap<>(Map.of("email", "a@b.com", "page", "/x"));

        EventDTO e = event("u1", false, custom);
        service.apply(e);

        assertThat(e.getUserId()).isNull();
        assertThat(custom).doesNotContainKey("email").containsKey("page");
        verify(metrics).incrementConsentDenied();
    }

    @Test
    void hashesUserIdAndPiiFieldsWhenEnabled() {
        properties.getPrivacy().setHashUserId(true);
        properties.getPrivacy().setPiiFields(List.of("email"));
        Map<String, Object> custom = new HashMap<>(Map.of("email", "a@b.com"));

        EventDTO e = event("u1", true, custom);
        service.apply(e);

        assertThat(e.getUserId()).startsWith("h:");
        assertThat((String) custom.get("email")).startsWith("h:");
        verify(metrics).incrementPiiMasked();
    }

    @Test
    void heuristicMasksEmailAndPhone() {
        properties.getPrivacy().setMaskHeuristics(true);
        Map<String, Object> custom = new HashMap<>();
        custom.put("contact", "alice@example.com");
        custom.put("mobile", "13800001234");
        custom.put("note", "hello");

        EventDTO e = event("u1", true, custom);
        service.apply(e);

        assertThat(custom.get("contact")).isEqualTo("a***@example.com");
        assertThat((String) custom.get("mobile")).startsWith("***");
        assertThat(custom.get("note")).isEqualTo("hello");
        verify(metrics).incrementPiiMasked();
    }
}
