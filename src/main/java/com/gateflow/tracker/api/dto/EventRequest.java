package com.gateflow.tracker.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    /** 单批最大事件数,防止超大请求体放大攻击/内存压力。 */
    public static final int MAX_BATCH_SIZE = 500;

    @NotEmpty(message = "events list cannot be empty")
    @Size(max = MAX_BATCH_SIZE, message = "events list exceeds max batch size of " + MAX_BATCH_SIZE)
    @Valid
    private List<EventDTO> events;

    private String clientId;
}
