package com.xa.mass.transport.websocket.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameReadersTest {

    @Test
    void sessionReaderIgnoresRouteAddressWhenPresent() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader();

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket-1", identity.workerGroupId());
        assertEquals("worker-1", identity.workerId());
    }

    @Test
    void sessionReaderRequiresOnlyWorkerGroupAndWorkerId() {
        WebSocketSessionOpenFrameReader reader = new WebSocketSessionOpenFrameReader();

        WebSocketSessionIdentity identity = reader.readHandshake(
                "/ws?workerId=worker-1&workerGroupId=bucket-1"
        );

        assertTrue(identity.complete());
        assertEquals("bucket-1", identity.workerGroupId());
        assertEquals("worker-1", identity.workerId());
    }
}
