package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification;
import com.xa.mass.sdk.model.TaskWorkAttemptClosedSnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class QueueBackedTaskReviewReadModelWriterTest {

    @Test
    void recordItemsAcceptedSubmitsAcceptedEvent() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.terminalDefault());

        writer.recordItemsAccepted(
                "task-001",
                Map.of(),
                List.of(Map.of("eventCode", "probe.weather")),
                new TaskItemBatchAppendReceipt("task-001", 1, List.of("msg-001")),
                3);

        assertEquals(1, queue.events.size());
        TaskReviewItemsAcceptedEvent event = assertInstanceOf(TaskReviewItemsAcceptedEvent.class, queue.events.get(0));
        assertEquals("task-001", event.taskId());
        assertEquals(List.of("msg-001"), event.messageIds());
        assertEquals(3, event.maxRetryCount());
    }

    @Test
    void recordWorkFinalSubmitsTerminalEvent() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.terminalDefault());

        writer.recordWorkFinal(new TaskWorkFinalNotification(
                "task-001",
                Map.of(),
                new TaskWorkFinalSnapshot(
                        "task-001",
                        "msg-001",
                        "SUCCESS",
                        "BUSINESS_SUCCESS",
                        1,
                        3,
                        "probe.weather",
                        "worker-001",
                        "batch-001",
                        "attempt-002",
                        null,
                        null,
                        "payload://result/msg-001",
                        Instant.parse("2026-05-29T02:00:00Z"),
                        Instant.parse("2026-05-29T02:01:00Z"),
                        Instant.parse("2026-05-29T02:01:05Z"),
                        Instant.parse("2026-05-29T02:01:12Z"),
                        Instant.parse("2026-05-29T02:01:12Z"),
                        Map.of("ok", true))));

        assertEquals(1, queue.events.size());
        TaskReviewWorkTerminalEvent event = assertInstanceOf(TaskReviewWorkTerminalEvent.class, queue.events.get(0));
        assertEquals("task-001", event.taskId());
        assertEquals("msg-001", event.messageId());
        assertEquals("attempt-002", event.attemptId());
    }

    @Test
    void recordAttemptClosedSkipsAttemptClosedEventByDefault() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.terminalDefault());

        writer.recordAttemptClosed(new TaskWorkAttemptClosedNotification(
                "task-001",
                Map.of(),
                new TaskWorkAttemptClosedSnapshot(
                        "task-001",
                        "msg-001",
                        "attempt-001",
                        1,
                        "worker-stale",
                        "batch-001",
                        "EXPIRED",
                        "LEASE_EXPIRED")));

        assertEquals(0, queue.events.size());
    }

    @Test
    void recordAttemptClosedSubmitsAttemptClosedEventWhenDiagnostic() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.diagnosticDefault());

        writer.recordAttemptClosed(new TaskWorkAttemptClosedNotification(
                "task-001",
                Map.of(),
                new TaskWorkAttemptClosedSnapshot(
                        "task-001",
                        "msg-001",
                        "attempt-001",
                        1,
                        "worker-stale",
                        "batch-001",
                        "EXPIRED",
                        "LEASE_EXPIRED")));

        assertEquals(1, queue.events.size());
        TaskReviewAttemptClosedEvent event =
                assertInstanceOf(TaskReviewAttemptClosedEvent.class, queue.events.get(0));
        assertEquals("task-001", event.taskId());
        assertEquals("msg-001", event.messageId());
        assertEquals("attempt-001", event.attemptId());
        assertEquals("EXPIRED", event.status());
        assertEquals("LEASE_EXPIRED", event.finalReason());
    }

    @Test
    void taskSharedConfigCanOptIntoDiagnosticAttemptMaterialization() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.terminalDefault());

        writer.recordAttemptClosed(new TaskWorkAttemptClosedNotification(
                "task-001",
                Map.of(TaskReviewMaterializationPolicy.SHARED_CONFIG_KEY, "diagnostic"),
                new TaskWorkAttemptClosedSnapshot(
                        "task-001",
                        "msg-001",
                        "attempt-001",
                        1,
                        "worker-stale",
                        "batch-001",
                        "EXPIRED",
                        "LEASE_EXPIRED")));

        assertEquals(1, queue.events.size());
        assertInstanceOf(TaskReviewAttemptClosedEvent.class, queue.events.get(0));
    }

    @Test
    void taskSharedConfigCanDisableTerminalMaterialization() {
        CapturingQueue queue = new CapturingQueue(true);
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                queue,
                TaskReviewMaterializationPolicy.terminalDefault());

        writer.recordItemsAccepted(
                "task-001",
                Map.of(TaskReviewMaterializationPolicy.SHARED_CONFIG_KEY, "off"),
                List.of(Map.of("eventCode", "probe.weather")),
                new TaskItemBatchAppendReceipt("task-001", 1, List.of("msg-001")),
                3);

        assertEquals(0, queue.events.size());
    }

    @Test
    void queueRejectionDoesNotThrowThroughWriter() {
        QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                new CapturingQueue(false),
                TaskReviewMaterializationPolicy.terminalDefault());

        assertDoesNotThrow(() -> writer.recordItemsAccepted(
                "task-001",
                Map.of(),
                List.of(Map.of("eventCode", "probe.weather")),
                new TaskItemBatchAppendReceipt("task-001", 1, List.of("msg-001")),
                3));
    }

    private static final class CapturingQueue implements TaskReviewReportQueue {
        private final boolean accept;
        private final List<TaskReviewReportEvent> events = new ArrayList<>();

        private CapturingQueue(boolean accept) {
            this.accept = accept;
        }

        @Override
        public boolean submit(TaskReviewReportEvent event) {
            if (accept) {
                events.add(event);
            }
            return accept;
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            return true;
        }

        @Override
        public TaskReviewReportQueueStats snapshotStats() {
            return new TaskReviewReportQueueStats(events.size(), accept ? 0 : 1, events.size(), 0, 0, null);
        }

        @Override
        public void close() {
        }
    }
}
