package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryCommandTest {

    @Test
    void carriesOnlySelectedWorkerContentAndExecutionContext() {
        TaskDispatchContent content = content();
        TaskDispatchExecutionContext executionContext = executionContext();

        DeliveryCommand command = new DeliveryCommand(
                " command-1 ",
                " bucket-1 ",
                " worker-1 ",
                content,
                executionContext,
                100L,
                10L
        );

        assertEquals("command-1", command.getCommandId());
        assertEquals("bucket-1", command.getDeliveryBucketId());
        assertEquals("worker-1", command.getSelectedWorkerId());
        assertSame(content, command.getContent());
        assertSame(executionContext, command.getExecutionContext());
        assertEquals(100L, command.getDeadlineEpochMillis());
        assertEquals(10L, command.getCreatedAtEpochMillis());
    }

    @Test
    void rejectsMissingRequiredItemFields() {
        TaskDispatchContent content = content();
        TaskDispatchExecutionContext executionContext = executionContext();

        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                " ",
                "bucket-1",
                "worker-1",
                content,
                executionContext,
                0L,
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                "command-1",
                " ",
                "worker-1",
                content,
                executionContext,
                0L,
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                "command-1",
                "bucket-1",
                " ",
                content,
                executionContext,
                0L,
                0L
        ));
        assertThrows(NullPointerException.class, () -> new DeliveryCommand(
                "command-1",
                "bucket-1",
                "worker-1",
                null,
                executionContext,
                0L,
                0L
        ));
        assertThrows(NullPointerException.class, () -> new DeliveryCommand(
                "command-1",
                "bucket-1",
                "worker-1",
                content,
                null,
                0L,
                0L
        ));
    }

    @Test
    void contentKeepsExecutionPayloadSeparateFromRoutingFacts() {
        TaskDispatchContent content = new TaskDispatchContent(
                " task-1 ",
                " msg-1 ",
                " event-1 ",
                Map.of("target", "target-1"),
                Map.of("mode", "fast")
        );

        assertEquals("task-1", content.taskId());
        assertEquals("msg-1", content.messageId());
        assertEquals("event-1", content.eventCode());
        assertEquals(Map.of("target", "target-1"), content.input());
        assertEquals(Map.of("mode", "fast"), content.sharedConfig());
    }

    @Test
    void executionContextCarriesAttemptCorrelationNotTaskOrRouteFacts() {
        TaskDispatchExecutionContext context = new TaskDispatchExecutionContext(
                " attempt-1 ",
                2,
                1,
                " batch-1 "
        );

        assertEquals("attempt-1", context.attemptId());
        assertEquals(2, context.attemptNo());
        assertEquals(1, context.retryCount());
        assertEquals("batch-1", context.batchId());
    }

    private static TaskDispatchContent content() {
        return new TaskDispatchContent(
                "task-1",
                "msg-1",
                "event-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private static TaskDispatchExecutionContext executionContext() {
        return new TaskDispatchExecutionContext(
                "attempt-1",
                1,
                0,
                "batch-1"
        );
    }
}
