package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.WorkerDispatchItem;
import com.xa.mass.client.worker.handler.WorkerEventHandlerRuntime;
import com.xa.mass.client.worker.handler.WorkerEventHandlers;
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
                new WorkerEventHandlerRuntime(WorkerEventHandlers.builder()
                        .event("probe.phone.metadata", dispatch -> WorkerResult.success(Map.of(
                                "eventCode", dispatch.eventCode())))
                        .build()),
                WorkerSessionListener.NOOP);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("corr-1", processed.resultCorrelationRef().value());
        assertEquals("probe.phone.metadata", processed.invocation().eventCode());
        assertTrue(processed.result().success());
        assertEquals("probe.phone.metadata", processed.result().output().get("eventCode"));
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
                new WorkerEventHandlerRuntime(WorkerEventHandlers.builder()
                        .event("probe.phone.metadata", dispatch -> {
                            throw new IllegalStateException("handler failed");
                        })
                        .build()),
                listener);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertNotNull(observed.get());
        assertEquals("corr-1", observed.get().resultCorrelationRef().value());
        assertEquals("handler failed", observed.get().cause().getMessage());
        assertEquals("HANDLER_ERROR", processed.result().errorCode());
    }

    private static WorkerDispatchItem dispatch() {
        return new WorkerDispatchItem(
                "corr-1",
                "probe.phone.metadata",
                Map.of(),
                Map.of());
    }
}
