package com.xa.mass.client.worker.session;

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
                WorkerSessionListener.NOOP);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("corr-1", processed.resultCorrelationRef());
        assertEquals("probe.phone.metadata", processed.invocation().eventCode());
        assertTrue(processed.result().success());
        assertEquals("probe.phone.metadata", processed.result().result());
    }

    @Test
    void reportsHandlerFailureThroughSessionListener() {
        AtomicReference<WorkerSessionDispatchFailure> observed = new AtomicReference<>();
        WorkerSessionListener listener = new WorkerSessionListener() {
            @Override
            public void onHandlerFailure(WorkerSessionDispatchFailure failure) {
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
        assertEquals("corr-1", observed.get().resultCorrelationRef());
        assertEquals("handler failed", observed.get().cause().getMessage());
        assertEquals("HANDLER_ERROR", processed.result().resultCode());
    }

    @Test
    void missingHandlerReturnsStructuredFailureWithoutHandlerFailureCallback() {
        AtomicReference<WorkerSessionDispatchFailure> observed = new AtomicReference<>();
        WorkerDispatchProcessor processor = new WorkerDispatchProcessor(
                "worker-1",
                Map.of(),
                new WorkerSessionListener() {
                    @Override
                    public void onHandlerFailure(WorkerSessionDispatchFailure failure) {
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
                WorkerSessionListener.NOOP);

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
