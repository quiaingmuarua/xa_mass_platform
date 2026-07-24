package com.xa.mass.server.api.v1.model;

import java.util.Objects;

public record CommandResultResponse(
        RuntimeCommandStatus status,
        String reason
) {
    public CommandResultResponse {
        Objects.requireNonNull(status, "status");
    }
}
