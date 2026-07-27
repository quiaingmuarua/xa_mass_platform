package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TaskItemResultsLoadRequest(
        @NotNull
        @Size(min = 1, max = 1000)
        List<@NotBlank String> messageIds
) {
    public TaskItemResultsLoadRequest {
        if (messageIds != null) {
            messageIds = List.copyOf(messageIds);
        }
    }
}
