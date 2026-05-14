package com.gateflow.tracker.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventDTO {

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotBlank(message = "eventType is required")
    private String eventType;

    private String userId;
    private String anonymousId;

    @NotNull(message = "timestamp is required")
    private Long timestamp;

    private Long clientTime;

    private String platform;
    private String appVersion;
    private String sdkVersion;

    private PageData page;
    private SessionData session;
    private DeviceData device;
    private ContextData context;
    private EventData data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageData {
        private String url;
        private String title;
        private String referrer;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionData {
        private String sessionId;
        private Long startTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceData {
        private String userAgent;
        private Integer screenWidth;
        private Integer screenHeight;
        private String language;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextData {
        private String utmSource;
        private String utmMedium;
        private String utmCampaign;
        private String utmTerm;
        private String utmContent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventData {
        private Integer scrollDepth;
        private Long stayDuration;
        private String elementId;
        private String elementType;
        private String elementText;
        private Integer clickX;
        private Integer clickY;
        private Long exposureDuration;
        private Double exposureRatio;
        private Map<String, Object> custom;
    }
}
