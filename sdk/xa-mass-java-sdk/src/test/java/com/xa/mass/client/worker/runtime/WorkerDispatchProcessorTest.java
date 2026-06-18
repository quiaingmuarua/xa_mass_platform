package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerInvocation;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDispatchProcessorTest {
    @Test
    void invokesHandlerAndReturnsProcessedDispatch() {
        WorkerDispatchProcessor processor = new WorkerDispatchProcessor(
                "worker-1",
                Map.of("probe.phone.metadata", dispatch -> WorkerResult.success(dispatch.eventCode())),
                WorkerRuntimeListener.NOOP);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("corr-1", processed.resultCorrelationRef());
        assertEquals("probe.phone.metadata", processed.invocation().eventCode());
        assertTrue(processed.result().success());
        assertEquals("probe.phone.metadata", processed.result().result());
    }

    @Test
    void reportsHandlerFailureThroughSessionListener() {
        AtomicReference<WorkerRuntimeFailureEvent> observed = new AtomicReference<>();
        WorkerRuntimeListener listener = new WorkerRuntimeListener() {
            @Override
            public void onFailure(WorkerRuntimeFailureEvent failure) {
                observed.set(failure);
            }
        };
        WorkerDispatchProcessor processor = new WorkerDispatchProcessor(
                "worker-1",
                Map.of("probe.phone.metadata", dispatch -> {
                    throw new IllegalStateException("handler failed");
                }),
                listener);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertNotNull(observed.get());
        assertEquals(WorkerRuntimeFailureEvent.Kind.HANDLER, observed.get().kind());
        assertEquals("HANDLER_ERROR", observed.get().reason());
        assertEquals("corr-1", observed.get().resultCorrelationRef());
        assertEquals(IllegalStateException.class.getName(), observed.get().errorType());
        assertEquals("handler failed", observed.get().errorMessage());
        assertEquals("HANDLER_ERROR", processed.result().resultCode());
    }

    @Test
    void missingHandlerReturnsStructuredFailureWithoutHandlerFailureCallback() {
        AtomicReference<WorkerRuntimeFailureEvent> observed = new AtomicReference<>();
        WorkerDispatchProcessor processor = new WorkerDispatchProcessor(
                "worker-1",
                Map.of(),
                new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        observed.set(failure);
                    }
                });

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("NO_HANDLER", processed.result().resultCode());
        assertEquals(null, observed.get());
    }

    @Test
    void nullHandlerResultReturnsStructuredFailure() {
        WorkerDispatchProcessor processor = new WorkerDispatchProcessor(
                "worker-1",
                Map.of("probe.phone.metadata", dispatch -> null),
                WorkerRuntimeListener.NOOP);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("HANDLER_NULL_RESULT", processed.result().resultCode());
    }

    private static WorkerInvocation dispatch() {
        return new WorkerInvocation(
                "corr-1",
                "probe.phone.metadata",
                Map.of(),
                Map.of());
    }
}
