package com.xa.mass.transport.websocket.session;

import io.netty.channel.Channel;

import java.util.Objects;

public record WebSocketSessionRecord(
        String deliveryBucketId,
        String endpointAddress,
        String workerId,
        Channel channel
) {

    public WebSocketSessionRecord {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        endpointAddress = requireText(endpointAddress, "endpointAddress");
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(channel, "channel");
    }

    public String sessionHandle() {
        return channel.id().asShortText();
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
