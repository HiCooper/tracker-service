package com.gateflow.tracker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    private String sessionId;
    private String userId;
    private String anonymousId;
    private String platform;

    /** 所属 app(= 采集 clientId,与契约 key / 事件 app_code 一致)。 */
    private String appCode;

    private Instant startTime;
    private Instant endTime;
    private Long duration;

    private Integer pageViews;
    private Integer clicks;
    private Integer exposures;
    private Integer scrollDepthMax;

    private Boolean isBounce;
    private String bouncePage;

    private String firstPageUrl;
    private String lastPageUrl;

    private String utmSource;
    private String utmMedium;
    private String utmCampaign;

    private String deviceType;
    private String os;

    private Instant lastActiveAt;
}
