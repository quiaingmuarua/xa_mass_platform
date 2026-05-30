package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueBackedTaskReviewMaterializationIntegrationTest {

    @Test
    void queueBackedWriterMaterializesRowsReadableThroughExistingReadModel() {
        MapBackedTaskDetailStore store = new MapBackedTaskDetailStore();
        TaskReviewReadModel readModel = new TaskDetailStoreTaskReviewReadModel(store);
        try (InProcessTaskReviewReportQueue queue = new InProcessTaskReviewReportQueue(
                new TaskDetailStoreReviewMaterializer(store), 8)) {
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

    private static final class MapBackedTaskDetailStore implements TaskDetailStore {
        private final Map<String, Map<String, TaskMessageProjection>> messages = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Map<String, TaskMessageAttemptProjection>>> attempts =
                new ConcurrentHashMap<>();

        @Override
        public boolean upsertTaskMessageProjection(String taskId, TaskMessageProjection projection) {
            if (taskId == null || projection == null || projection.messageId() == null) {
                return false;
            }
            messages.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .put(projection.messageId(), projection);
            return true;
        }

        @Override
        public Optional<TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
            return Optional.ofNullable(messages.getOrDefault(taskId, Map.of()).get(messageId));
        }

        @Override
        public List<TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
            return messages.getOrDefault(taskId, Map.of())
                    .values()
                    .stream()
                    .sorted(Comparator.comparing(TaskMessageProjection::createTime,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(Math.max(0, limit))
                    .toList();
        }

        @Override
        public boolean upsertTaskMessageAttemptProjection(String taskId,
                                                          String messageId,
                                                          TaskMessageAttemptProjection projection) {
            if (taskId == null || messageId == null || projection == null || projection.attemptId() == null) {
                return false;
            }
            attempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(messageId, ignored -> new ConcurrentHashMap<>())
                    .put(projection.attemptId(), projection);
            return true;
        }

        @Override
        public List<TaskMessageAttemptProjection> getTaskMessageAttemptProjections(String taskId, String messageId) {
            return new ArrayList<>(attempts.getOrDefault(taskId, Map.of())
                    .getOrDefault(messageId, Map.of())
                    .values());
        }

        @Override
        public Optional<TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                            String messageId) {
            return getTaskMessageAttemptProjections(taskId, messageId)
                    .stream()
                    .reduce((ignored, latest) -> latest);
        }

        @Override
        public TaskMessageStats getTaskMessageStats(String taskId) {
            List<TaskMessageProjection> projections = getTaskMessageProjections(taskId, Integer.MAX_VALUE);
            long success = projections.stream().filter(p -> p.status() == TaskMessageProjectionStatus.SUCCESS).count();
            long failed = projections.stream().filter(p -> p.status() == TaskMessageProjectionStatus.FAILED).count();
            long expired = projections.stream().filter(p -> p.status() == TaskMessageProjectionStatus.EXPIRED).count();
            long processing = projections.stream()
                    .filter(p -> p.status() != null && !p.status().isFinal() && p.status() != TaskMessageProjectionStatus.INIT)
                    .count();
            return new TaskMessageStats(projections.size(), success, failed, expired, processing);
        }

        @Override
        public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
            long total = getTaskMessageAttemptProjections(taskId, messageId).size();
            return new TaskMessageAttemptStats(total, 0, 0, 0, 0);
        }

        @Override
        public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
            long total = attempts.getOrDefault(taskId, Map.of())
                    .values()
                    .stream()
                    .mapToLong(Map::size)
                    .sum();
            return new TaskMessageAttemptStats(total, 0, 0, 0, 0);
        }
    }
}
