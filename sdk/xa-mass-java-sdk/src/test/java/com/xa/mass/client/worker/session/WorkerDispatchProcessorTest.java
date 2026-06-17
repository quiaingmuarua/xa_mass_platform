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
                                "worker", dispatch.workerId())))
                        .build()),
                WorkerSessionListener.NOOP);

        WorkerDispatchProcessor.ProcessedDispatch processed = processor.process(dispatch());

        assertEquals("task-1", processed.dispatch().taskId());
        assertEquals("message-1", processed.dispatch().messageId());
        assertTrue(processed.result().success());
        assertEquals("worker-1", processed.result().output().get("worker"));
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
        assertEquals("message-1", observed.get().dispatch().messageId());
        assertEquals("handler failed", observed.get().cause().getMessage());
        assertEquals("HANDLER_ERROR", processed.result().errorCode());
    }

    private static WorkerDispatchItem dispatch() {
        return new WorkerDispatchItem(
                "task-1",
                "message-1",
                "probe.phone.metadata",
                "probe-task",
                "demo",
                "user-1",
                0,
                null,
                "batch-1",
                Map.of(),
                Map.of());
    }
}
