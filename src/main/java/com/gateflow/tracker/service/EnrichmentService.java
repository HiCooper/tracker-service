package com.gateflow.tracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.model.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EnrichmentService {

    private final ObjectMapper objectMapper;

    /**
     * 对事件数据进行增强
     * 增强失败时返回原始事件（不丢失数据）
     */
    public EventRecord enrich(EventDTO event) {
        try {
            return EventRecord.builder()
                    .eventId(event.getEventId())
                    .eventType(event.getEventType())
                    .userId(event.getUserId())
                    .anonymousId(event.getAnonymousId())
                    .sessionId(event.getSession() != null ? event.getSession().getSessionId() : null)
                    .timestamp(event.getTimestamp() != null ? Instant.ofEpochMilli(event.getTimestamp()) : Instant.now())
                    .clientTime(event.getClientTime() != null ? Instant.ofEpochMilli(event.getClientTime()) : null)
                    .receivedAt(Instant.now())
                    .platform(event.getPlatform())
                    .appVersion(event.getAppVersion())
                    .sdkVersion(event.getSdkVersion())

                    // 页面信息
                    .pageUrl(event.getPage() != null ? event.getPage().getUrl() : null)
                    .pageTitle(event.getPage() != null ? event.getPage().getTitle() : null)
                    .pageReferrer(event.getPage() != null ? event.getPage().getReferrer() : null)

                    // 设备信息
                    .deviceType(event.getDevice() != null ? parseDeviceType(event.getDevice().getUserAgent()) : null)
                    .os(event.getDevice() != null ? parseOS(event.getDevice().getUserAgent()) : null)
                    .browser(event.getDevice() != null ? parseBrowser(event.getDevice().getUserAgent()) : null)
                    .screenWidth(event.getDevice() != null ? event.getDevice().getScreenWidth() : null)
                    .screenHeight(event.getDevice() != null ? event.getDevice().getScreenHeight() : null)
                    .language(event.getDevice() != null ? event.getDevice().getLanguage() : null)

                    // 交互数据
                    .elementId(event.getData() != null ? event.getData().getElementId() : null)
                    .elementType(event.getData() != null ? event.getData().getElementType() : null)
                    .elementText(event.getData() != null ? event.getData().getElementText() : null)
                    .clickX(event.getData() != null ? event.getData().getClickX() : null)
                    .clickY(event.getData() != null ? event.getData().getClickY() : null)
                    .scrollDepth(event.getData() != null ? event.getData().getScrollDepth() : null)
                    .stayDuration(event.getData() != null ? event.getData().getStayDuration() : null)
                    .exposureDuration(event.getData() != null ? event.getData().getExposureDuration() : null)
                    .exposureRatio(event.getData() != null ? event.getData().getExposureRatio() : null)

                    // 归因数据
                    .utmSource(event.getContext() != null ? event.getContext().getUtmSource() : null)
                    .utmMedium(event.getContext() != null ? event.getContext().getUtmMedium() : null)
                    .utmCampaign(event.getContext() != null ? event.getContext().getUtmCampaign() : null)
                    .utmTerm(event.getContext() != null ? event.getContext().getUtmTerm() : null)
                    .utmContent(event.getContext() != null ? event.getContext().getUtmContent() : null)

                    // 自定义属性
                    .properties(event.getData() != null ? event.getData().getCustom() : null)

                    .build();
        } catch (Exception e) {
            log.warn("Event enrichment failed for eventId={}: {}", event.getEventId(), e.getMessage());
            // 增强失败时返回原始事件
            return EventRecord.builder()
                    .eventId(event.getEventId())
                    .eventType(event.getEventType())
                    .userId(event.getUserId())
                    .anonymousId(event.getAnonymousId())
                    .timestamp(event.getTimestamp() != null ? Instant.ofEpochMilli(event.getTimestamp()) : Instant.now())
                    .receivedAt(Instant.now())
                    .build();
        }
    }

    private String parseDeviceType(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "mobile";
        } else if (ua.contains("tablet") || ua.contains("ipad")) {
            return "tablet";
        }
        return "desktop";
    }

    private String parseOS(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os") || ua.contains("darwin")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        if (ua.contains("android")) return "Android";
        if (ua.contains("ios") || ua.contains("iphone") || ua.contains("ipad")) return "iOS";
        return "unknown";
    }

    private String parseBrowser(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("edg/")) return "Edge";
        if (ua.contains("chrome/") && !ua.contains("chromium/")) return "Chrome";
        if (ua.contains("firefox/")) return "Firefox";
        if (ua.contains("safari/") && !ua.contains("chrome/")) return "Safari";
        if (ua.contains("opera/") || ua.contains("opr/")) return "Opera";
        return "unknown";
    }

    private String serializeProperties(EventDTO.EventData data) {
        if (data == null || data.getCustom() == null || data.getCustom().isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> props = new HashMap<>(data.getCustom());
            // 添加标准字段到 properties
            if (data.getClickX() != null) props.put("clickX", data.getClickX());
            if (data.getClickY() != null) props.put("clickY", data.getClickY());
            if (data.getScrollDepth() != null) props.put("scrollDepth", data.getScrollDepth());
            if (data.getStayDuration() != null) props.put("stayDuration", data.getStayDuration());
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize custom properties", e);
            return "{}";
        }
    }
}
