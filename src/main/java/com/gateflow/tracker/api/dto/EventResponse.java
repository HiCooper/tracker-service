package com.gateflow.tracker.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {

    private int code;
    private String message;
    private ResponseData data;

    public static EventResponse success(int accepted, int duplicate, int rejected) {
        return EventResponse.builder()
                .code(200)
                .message("success")
                .data(ResponseData.builder()
                        .accepted(accepted)
                        .duplicate(duplicate)
                        .rejected(rejected)
                        .dlq(0)
                        .build())
                .build();
    }

    public static EventResponse error(String message) {
        return EventResponse.builder()
                .code(500)
                .message(message)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseData {
        private int accepted;
        private int duplicate;
        private int rejected;
        private int dlq;
    }
}
