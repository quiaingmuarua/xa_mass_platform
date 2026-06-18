package com.xa.mass.transport.websocket.session;

public record WebSocketServerSession(
        String workerId,
        String sessionHandle
) {

    public WebSocketServerSession {
        workerId = requireText(workerId, "workerId");
        sessionHandle = requireText(sessionHandle, "sessionHandle");
    }

    static WebSocketServerSession from(WebSocketSessionRecord record) {
        if (record == null) {
            return null;
        }
        return new WebSocketServerSession(
                record.workerId(),
                record.sessionHandle()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
