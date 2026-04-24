package com.xa.mass.gateway.dispatcher.bridge;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorkerControlEventRequestBridgeTest {

    @Test
    void handleControlEventRequestPassesCanonicalRequestAndPrincipalDirectlyToDispatcher() {
        AtomicReference<EventRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<EventPrincipal> capturedPrincipal = new AtomicReference<>();
        WorkerControlEventRequestBridge bridge = new WorkerControlEventRequestBridge((request, principal) -> {
            capturedRequest.set(request);
            capturedPrincipal.set(principal);
            return EventResponse.success(Map.of("accepted", true), request.getRequestId());
        });

        EventRequest request = EventRequest.builder()
                .event("mock.state.get")
                .project("demoApp")
                .requestId("req-1")
                .headers(Map.of("traceId", "trace-1"))
                .payload(Map.of("verbose", true))
                .build();
        EventPrincipal principal = EventPrincipal.builder()
                .clientId("client-1")
                .userId("user-1")
                .build();

        EventResponse response = bridge.handleControlEventRequest(request, principal);

        assertSame(request, capturedRequest.get());
        assertSame(principal, capturedPrincipal.get());
        assertNotNull(response);
        assertEquals("req-1", response.getRequestId());
        assertEquals(Map.of("accepted", true), response.getData());
    }
}
