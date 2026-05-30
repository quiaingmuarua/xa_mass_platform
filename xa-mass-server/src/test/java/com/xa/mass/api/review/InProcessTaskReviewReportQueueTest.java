package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessTaskReviewReportQueueTest {

    @Test
    void appliesSubmittedEventsInOrderAndReportsIdle() {
        List<String> appliedTaskIds = java.util.Collections.synchronizedList(new ArrayList<>());
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(
                event -> appliedTaskIds.add(event.taskId()), 4)) {

            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-1", List.of(), List.of(), 0, 0)));
            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-2", List.of(), List.of(), 0, 0)));

            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(List.of("task-1", "task-2"), appliedTaskIds);
            assertEquals(2, queue.snapshotStats().submitted());
            assertEquals(2, queue.snapshotStats().applied());
            assertEquals(0, queue.snapshotStats().failed());
            assertEquals(0, queue.snapshotStats().pending());
        }
    }

    @Test
    void rejectsWhenBoundedQueueIsFull() throws Exception {
        CountDownLatch firstEventStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(event -> {
            firstEventStarted.countDown();
            try {
                releaseFirstEvent.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1)) {

            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-1", List.of(), List.of(), 0, 0)));
            assertTrue(firstEventStarted.await(2, TimeUnit.SECONDS));
            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-2", List.of(), List.of(), 0, 0)));
            assertFalse(queue.submit(new TaskReviewItemsAcceptedEvent("task-3", List.of(), List.of(), 0, 0)));

            releaseFirstEvent.countDown();
            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(2, queue.snapshotStats().submitted());
            assertEquals(1, queue.snapshotStats().rejected());
        }
    }

    @Test
    void materializerFailureIsCountedAndDoesNotPreventIdle() {
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(event -> {
            throw new IllegalStateException("boom");
        }, 2)) {

            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-1", List.of(), List.of(), 0, 0)));

            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
            TaskReviewReportQueueStats stats = queue.snapshotStats();
            assertEquals(1, stats.submitted());
            assertEquals(0, stats.applied());
            assertEquals(1, stats.failed());
            assertEquals("boom", stats.lastError());
        }
    }

    @Test
    void awaitIdleTimesOutWhenMaterializerIsStillApplying() throws Exception {
        CountDownLatch firstEventStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(event -> {
            firstEventStarted.countDown();
            try {
                releaseFirstEvent.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1)) {

            assertTrue(queue.submit(new TaskReviewItemsAcceptedEvent("task-1", List.of(), List.of(), 0, 0)));
            assertTrue(firstEventStarted.await(2, TimeUnit.SECONDS));
            assertFalse(queue.awaitIdle(Duration.ofMillis(25)));

            releaseFirstEvent.countDown();
            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
        }
    }

    @Test
    void acceptedEventCopiesCallerOwnedMutableItemsBeforeEnqueue() {
        Map<String, Object> firstItem = new LinkedHashMap<>();
        firstItem.put("eventCode", "before");
        firstItem.put("optionalValue", null);
        List<Map<String, Object>> acceptedItems = new ArrayList<>();
        acceptedItems.add(firstItem);
        TaskItemBatchAppendReceipt receipt = new TaskItemBatchAppendReceipt("task-1", 1, List.of("msg-1"));
        List<TaskReviewReportEvent> appliedEvents = java.util.Collections.synchronizedList(new ArrayList<>());

        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(appliedEvents::add, 2)) {
            assertTrue(queue.submit(TaskReviewItemsAcceptedEvent.from("task-1", acceptedItems, receipt, 3)));
            firstItem.put("eventCode", "after");
            acceptedItems.clear();

            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
        }

        assertEquals(1, appliedEvents.size());
        TaskReviewItemsAcceptedEvent event = assertInstanceOf(TaskReviewItemsAcceptedEvent.class, appliedEvents.get(0));
        assertEquals("before", event.acceptedItems().get(0).get("eventCode"));
        assertNull(event.acceptedItems().get(0).get("optionalValue"));
        assertEquals(List.of("msg-1"), event.messageIds());
        assertEquals(3, event.maxRetryCount());
    }
}
