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
public class DLQEntry {

    private String eventId;
    private String eventType;
    private String userId;
    private String eventJson;
    private String reason;
    private Instant failedAt;
    private Integer retryCount;
    private Instant nextRetryAt;
}
