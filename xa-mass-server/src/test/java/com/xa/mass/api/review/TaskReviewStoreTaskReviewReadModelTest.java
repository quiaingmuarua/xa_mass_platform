package com.xa.mass.api.review;

import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskReviewStoreTaskReviewReadModelTest {

    @Test
    void readModelLoadsStatsPreviewAndAttemptsFromServerStore() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreTaskReviewReadModel readModel = new TaskReviewStoreTaskReviewReadModel(store);
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 10, 0);
        store.upsertItem("task-001", new TaskReviewItem(
                "msg-001",
                "probe.weather",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                null,
                1,
                3,
                now,
                now.plusSeconds(1),
                now.plusSeconds(2),
                now.plusSeconds(3),
                now.plusSeconds(3),
                Map.of("eventCode", "probe.weather"),
                "worker-001",
                "batch-001",
                "attempt-002",
                null,
                null,
                Map.of("ok", true)));
        store.upsertAttempt("task-001", "msg-001", new TaskReviewReadModel.TaskReviewAttempt(
                "attempt-002",
                "task-001",
                "msg-001",
                2,
                "worker-001",
                "batch-001",
                "SUCCEEDED",
                "SUCCESS",
                null,
                null,
                Map.of("ok", true)));

        TaskReviewReadModel.TaskReviewSnapshot snapshot = readModel.loadReview("task-001", 10);

        assertEquals(1, snapshot.stats().totalItems());
        assertEquals(1, snapshot.stats().successItems());
        assertEquals(1, snapshot.preview().size());
        assertEquals("probe.weather", snapshot.preview().get(0).eventCode());
        assertEquals(1, readModel.loadAttempts("task-001", "msg-001").size());
        assertEquals("SUCCEEDED", readModel.loadAttempts("task-001", "msg-001").get(0).status());
    }
}
