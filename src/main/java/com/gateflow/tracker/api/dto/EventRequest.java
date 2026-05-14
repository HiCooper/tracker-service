package com.gateflow.tracker.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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

    @NotEmpty(message = "events list cannot be empty")
    @Valid
    private List<EventDTO> events;

    private String clientId;
}
