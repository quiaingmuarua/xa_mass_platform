package com.xa.mass.api.review;

import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDetailStoreTaskReviewReadModelTest {

    @Mock
    private TaskDetailStore taskDetailStore;

    @Test
    void recordWorkFinalWritesDispatchAttemptAndTimingEvidence() {
        TaskDetailStoreTaskReviewReadModel readModel = new TaskDetailStoreTaskReviewReadModel(taskDetailStore);
        TaskDetailStore.TaskMessageProjection previous = new TaskDetailStore.TaskMessageProjection(
                "msg-001",
                "task-001",
                Map.of("eventCode", "chatbot.reply", "text", "hello"),
                null,
                TaskMessageProjectionStatus.INIT,
                null,
                LocalDateTime.of(2026, 5, 29, 10, 0),
                LocalDateTime.of(2026, 5, 29, 10, 0),
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(taskDetailStore.getTaskMessageProjection("task-001", "msg-001"))
                .thenReturn(Optional.of(previous));

        Instant assigned = Instant.parse("2026-05-29T02:01:00Z");
        Instant started = Instant.parse("2026-05-29T02:01:05Z");
        Instant completed = Instant.parse("2026-05-29T02:01:12Z");
        readModel.recordWorkFinal(new TaskWorkFinalNotification(
                "task-001",
                Map.of(),
                new TaskWorkFinalSnapshot(
                        "task-001",
                        "msg-001",
                        "SUCCESS",
                        "BUSINESS_SUCCESS",
                        1,
                        3,
                        "chatbot.reply",
                        "worker-001",
                        "batch-001",
                        "attempt-002",
                        null,
                        null,
                        "payload://result/msg-001",
                        Instant.parse("2026-05-29T02:00:00Z"),
                        assigned,
                        started,
                        completed,
                        completed,
                        Map.of("ok", true)
                )
        ));

        ArgumentCaptor<TaskDetailStore.TaskMessageProjection> messageCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageProjection.class);
        verify(taskDetailStore).upsertTaskMessageProjection(eq("task-001"), messageCaptor.capture());
        TaskDetailStore.TaskMessageProjection message = messageCaptor.getValue();
        assertEquals(TaskMessageProjectionStatus.SUCCESS, message.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, message.finalReason());
        assertEquals(1, message.retryCount());
        assertEquals(3, message.maxRetryCount());
        assertEquals("attempt-002", message.latestAttemptId());
        assertEquals("worker-001", message.latestAttemptWorkerId());
        assertEquals("batch-001", message.latestAttemptBatchId());
        assertEquals(asLocalDateTime(assigned), message.assignedTime());
        assertEquals(asLocalDateTime(started), message.startTime());
        assertEquals(asLocalDateTime(completed), message.completeTime());
        assertEquals(Map.of("ok", true), message.output());

        ArgumentCaptor<TaskDetailStore.TaskMessageAttemptProjection> attemptCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageAttemptProjection.class);
        verify(taskDetailStore).upsertTaskMessageAttemptProjection(
                eq("task-001"),
                eq("msg-001"),
                attemptCaptor.capture());
        TaskDetailStore.TaskMessageAttemptProjection attempt = attemptCaptor.getValue();
        assertEquals("attempt-002", attempt.attemptId());
        assertEquals(2, attempt.attemptNo());
        assertEquals("worker-001", attempt.workerId());
        assertEquals("batch-001", attempt.batchId());
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, attempt.status());
        assertEquals(TaskMessageAttemptProjectionFinalReason.SUCCESS, attempt.finalReason());
        assertEquals(Map.of("ok", true), attempt.output());
    }

    private static LocalDateTime asLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
