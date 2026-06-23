package com.xa.mass.transport.websocket.frame;

/**
 * Channel-bound worker session identity for the WebSocket adapter.
 */
public record WebSocketSessionIdentity(String workerGroupId, String workerId) {

    public boolean complete() {
        return hasText(workerGroupId) && hasText(workerId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
