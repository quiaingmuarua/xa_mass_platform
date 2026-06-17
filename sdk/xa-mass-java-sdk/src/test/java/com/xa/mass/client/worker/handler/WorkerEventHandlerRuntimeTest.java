package com.xa.mass.client.worker.handler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerEventHandlerRuntimeTest {

    @Test
    void invokesHandlerRegisteredForEventCode() {
        WorkerEventHandlers handlers = WorkerEventHandlers.builder()
                .event("probe.phone.metadata", dispatch -> WorkerResult.success(Map.of(
                        "eventCode", dispatch.eventCode()
                )))
                .build();

        WorkerEventInvocation invocation = new WorkerEventHandlerRuntime(handlers).invoke(dispatch("probe.phone.metadata"));

        assertFalse(invocation.handlerFailed());
        assertTrue(invocation.result().success());
        assertEquals("probe.phone.metadata", invocation.result().output().get("eventCode"));
    }

    @Test
    void keepsHandlerRegistriesInstanceScoped() {
        WorkerEventHandlerRuntime first = new WorkerEventHandlerRuntime(WorkerEventHandlers.builder()
                .event("event-a", dispatch -> WorkerResult.success(Map.of("runtime", "first")))
                .build());
        WorkerEventHandlerRuntime second = new WorkerEventHandlerRuntime(WorkerEventHandlers.builder()
                .event("event-b", dispatch -> WorkerResult.success(Map.of("runtime", "second")))
                .build());

        assertTrue(first.invoke(dispatch("event-a")).result().success());
        assertEquals("NO_HANDLER", first.invoke(dispatch("event-b")).result().errorCode());
        assertEquals("NO_HANDLER", second.invoke(dispatch("event-a")).result().errorCode());
        assertTrue(second.invoke(dispatch("event-b")).result().success());
    }

    @Test
    void convertsUnknownNullAndExceptionResultsIntoStructuredFailures() {
        WorkerEventHandlers handlers = WorkerEventHandlers.builder()
                .event("null-event", dispatch -> null)
                .event("boom-event", dispatch -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        WorkerEventHandlerRuntime runtime = new WorkerEventHandlerRuntime(handlers);

        WorkerEventInvocation unknown = runtime.invoke(dispatch("missing-event"));
        assertFalse(unknown.handlerFailed());
        assertEquals("NO_HANDLER", unknown.result().errorCode());

        WorkerEventInvocation nullResult = runtime.invoke(dispatch("null-event"));
        assertFalse(nullResult.handlerFailed());
        assertEquals("HANDLER_NULL_RESULT", nullResult.result().errorCode());

        WorkerEventInvocation exception = runtime.invoke(dispatch("boom-event"));
        assertTrue(exception.handlerFailed());
        assertInstanceOf(IllegalStateException.class, exception.failure());
        assertEquals("HANDLER_ERROR", exception.result().errorCode());
        assertEquals(IllegalStateException.class.getName(), exception.result().output().get("exception"));
    }

    private static WorkerInvocation dispatch(String eventCode) {
        return new WorkerInvocation(eventCode, null, null);
    }
}
