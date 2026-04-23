package com.xa.mass.command.event;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMassEventRuntimeTest {

    @Test
    void registeredEventCanBeDispatchedAndKeepsRequestId() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        runtime.register(
                CoreEventDescriptor.builder()
                        .event("platform.test.echo")
                        .summary("Echo payload")
                        .build(),
                (request, principal) -> CoreEventResponse.success(
                        Map.of("event", request.getEvent(), "clientId", principal.clientId()),
                        request.getRequestId())
        );

        CoreEventResponse response = runtime.dispatch(
                CoreEventRequest.builder()
                        .event("platform.test.echo")
                        .requestId("req-1")
                        .payload(Map.of("value", "hello"))
                        .build(),
                new CoreEventPrincipal("client-a", "user-a")
        );

        assertTrue(response.isSuccess());
        assertEquals("req-1", response.getRequestId());
        assertEquals("platform.test.echo", runtime.getDescriptor("platform.test.echo").getEvent());
        assertEquals(1, runtime.listDescriptors().size());
    }

    @Test
    void duplicateEventRegistrationFailsFast() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        CoreEventDescriptor descriptor = CoreEventDescriptor.builder()
                .event("platform.test.duplicate")
                .build();

        runtime.register(descriptor, (request, principal) -> CoreEventResponse.success(Boolean.TRUE, request.getRequestId()));

        assertThrows(IllegalStateException.class,
                () -> runtime.register(descriptor, (request, principal) -> CoreEventResponse.success(Boolean.TRUE, request.getRequestId())));
    }

    @Test
    void unknownOrDisabledEventsAreRejected() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        runtime.register(
                CoreEventDescriptor.builder()
                        .event("platform.test.disabled")
                        .enabled(false)
                        .build(),
                (request, principal) -> CoreEventResponse.success(Boolean.TRUE, request.getRequestId())
        );

        CoreEventResponse unknown = runtime.dispatch(
                CoreEventRequest.builder().event("missing.event").requestId("req-missing").build(),
                null
        );
        CoreEventResponse disabled = runtime.dispatch(
                CoreEventRequest.builder().event("platform.test.disabled").requestId("req-disabled").build(),
                null
        );

        assertFalse(unknown.isSuccess());
        assertEquals("UNKNOWN_EVENT", unknown.getCode());
        assertFalse(disabled.isSuccess());
        assertEquals("EVENT_DISABLED", disabled.getCode());
    }
}
