package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueBackedTaskReviewMaterializationIntegrationTest {

    @Test
    void queueBackedWriterMaterializesRowsReadableThroughServerReviewStore() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewReadModel readModel = new TaskReviewStoreTaskReviewReadModel(store);
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(
                new TaskReviewStoreMaterializer(store), 8)) {
            QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(queue);

            writer.recordItemsAccepted(
                    "task-001",
                    List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                    new TaskItemBatchAppendReceipt("task-001", 1, List.of("msg-001")),
                    3);

            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
            List<TaskReviewReadModel.TaskReviewItem> acceptedItems = readModel.loadItems("task-001", 10);
            assertEquals(1, acceptedItems.size());
            assertEquals("INIT", acceptedItems.get(0).status());
            assertEquals("probe.weather", acceptedItems.get(0).eventCode());

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

            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));
            List<TaskReviewReadModel.TaskReviewItem> finalItems = readModel.loadItems("task-001", 10);
            assertEquals(1, finalItems.size());
            TaskReviewReadModel.TaskReviewItem item = finalItems.get(0);
            assertEquals("SUCCESS", item.status());
            assertEquals("BUSINESS_SUCCESS", item.finalReason());
            assertEquals("attempt-002", item.attemptId());
            assertEquals("worker-001", item.workerId());
            assertEquals("batch-001", item.batchId());
            assertEquals(Map.of("ok", true), item.output());

            List<TaskReviewReadModel.TaskReviewAttempt> attempts = readModel.loadAttempts("task-001", "msg-001");
            assertEquals(1, attempts.size());
            assertEquals("attempt-002", attempts.get(0).attemptId());
            assertEquals("SUCCEEDED", attempts.get(0).status());
        }
    }
}
