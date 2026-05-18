package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryTaskDispatchHandoffTest {

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTaskDispatchHandoff(0));
    }

    @Test
    void submitThenPollReturnsSameBatch() throws Exception {
        InMemoryTaskDispatchHandoff handoff = new InMemoryTaskDispatchHandoff(2);
        TaskDispatchBatch batch = batch("task-1", "msg-1");

        handoff.submit(batch);

        TaskDispatchBatch polled = handoff.poll(50L);
        assertSame(batch, polled);
        assertNull(handoff.poll(10L));
    }

    @Test
    void shutdownKeepsQueuedBatchesDrainableButRejectsNewSubmit() throws Exception {
        InMemoryTaskDispatchHandoff handoff = new InMemoryTaskDispatchHandoff(2);
        TaskDispatchBatch batch = batch("task-2", "msg-2");
        handoff.submit(batch);

        handoff.shutdown();

        assertSame(batch, handoff.poll(50L));
        assertNull(handoff.poll(10L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> handoff.submit(batch("task-3", "msg-3")));
        assertEquals("task dispatch handoff is stopped", error.getMessage());
    }

    private static TaskDispatchBatch batch(String taskId, String messageId) {
        return new TaskDispatchBatch(
                new TaskDispatchContext(
                        taskId,
                        "task-" + taskId,
                        "demo-project",
                        "demo-user",
                        "demo.event",
                        Map.of("routingCode", "us")
                ),
                List.of(new TaskDispatchBinding(
                        taskId,
                        messageId,
                        "demo.event",
                        Map.of("routingCode", "us"),
                        null,
                        0,
                        "attempt-" + messageId,
                        1,
                        null,
                        "worker-" + messageId,
                        "batch-" + messageId
                ))
        );
    }
}
