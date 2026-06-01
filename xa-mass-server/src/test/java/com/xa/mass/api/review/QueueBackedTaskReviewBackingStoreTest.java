package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueBackedTaskReviewBackingStoreTest {

    @Test
    void queuedMaterializerWorksWithInMemoryReviewStoreBacking() {
        assertQueuedMaterialization(new InMemoryTaskReviewStore());
    }

    @Test
    void queuedMaterializerWorksWithJdbcReviewStoreBacking() {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(JdbcStorageMode.JDBC_H2, jdbcUrl, "sa", "")) {
            assertQueuedMaterialization(new JdbcTaskReviewStore(runtime.dataSource()));
        }
    }

    private static void assertQueuedMaterialization(TaskReviewStore store) {
        TaskReviewReadModel readModel = new TaskReviewStoreTaskReviewReadModel(store);
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(
                new TaskReviewStoreMaterializer(store), 8)) {
            QueueBackedTaskReviewReadModelWriter writer = new QueueBackedTaskReviewReadModelWriter(
                    queue,
                    TaskReviewMaterializationPolicy.terminalDefault());

            writer.recordItemsAccepted(
                    "task-001",
                    Map.of(),
                    List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                    new TaskItemBatchAppendReceipt("task-001", 1, List.of("msg-001")),
                    3);
            assertTrue(queue.awaitIdle(Duration.ofSeconds(2)));

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

            List<TaskReviewReadModel.TaskReviewItem> items = readModel.loadItems("task-001", 10);
            assertEquals(1, items.size());
            assertEquals("SUCCESS", items.get(0).status());
            assertEquals("probe.weather", items.get(0).eventCode());
            assertEquals(Map.of("ok", true), items.get(0).output());

            List<TaskReviewReadModel.TaskReviewAttempt> attempts = readModel.loadAttempts("task-001", "msg-001");
            assertEquals(1, attempts.size());
            assertEquals("attempt-002", attempts.get(0).attemptId());
            assertEquals("SUCCEEDED", attempts.get(0).status());
        }
    }
}
