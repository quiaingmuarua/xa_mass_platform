package com.xa.mass.sdk.model;

public record WorkerCommandAcknowledgementRequest(
        String commandId,
        String status,
        String reason
) {
}
