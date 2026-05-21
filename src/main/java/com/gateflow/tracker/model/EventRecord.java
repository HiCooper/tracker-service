package com.gateflow.tracker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EventRecord {

    private String eventId;
    private String eventType;
    private String userId;
    private String anonymousId;
    private String sessionId;

    private Instant timestamp;
    private Instant clientTime;
    private Instant receivedAt;

    private String platform;
    private String appVersion;
    private String sdkVersion;

    private String pageUrl;
    private String pageTitle;
    private String pageReferrer;

    private String spma;
    private String spmb;
    private String spmc;
    private String spmd;

    private String deviceType;
    private String os;
    private String browser;
    private Integer screenWidth;
    private Integer screenHeight;
    private String language;

    private String elementId;
    private String elementType;
    private String elementText;
    private Integer clickX;
    private Integer clickY;
    private Integer scrollDepth;
    private Long stayDuration;
    private Long exposureDuration;
    private Double exposureRatio;

    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmTerm;
    private String utmContent;

    private List<String> expIds;
    private List<String> variants;

    private Map<String, Object> properties;
}
