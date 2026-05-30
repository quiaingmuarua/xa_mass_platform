package com.xa.mass.api.review;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDetailStoreReviewMaterializerTest {

    @Mock
    private TaskDetailStore taskDetailStore;

    @Test
    void acceptedEventWritesInitMessageProjection() {
        when(taskDetailStore.getTaskMessageProjection("task-001", "msg-001"))
                .thenReturn(Optional.empty());
        TaskDetailStoreReviewMaterializer materializer = new TaskDetailStoreReviewMaterializer(taskDetailStore);

        materializer.apply(new TaskReviewItemsAcceptedEvent(
                "task-001",
                List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                List.of("msg-001"),
                1,
                3));

        ArgumentCaptor<TaskDetailStore.TaskMessageProjection> messageCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageProjection.class);
        verify(taskDetailStore).upsertTaskMessageProjection(eq("task-001"), messageCaptor.capture());
        TaskDetailStore.TaskMessageProjection message = messageCaptor.getValue();
        assertEquals("msg-001", message.messageId());
        assertEquals(Map.of("eventCode", "probe.weather", "city", "shenzhen"), message.input());
        assertEquals(TaskMessageProjectionStatus.INIT, message.status());
        assertEquals(0, message.retryCount());
        assertEquals(3, message.maxRetryCount());
        assertNull(message.finalReason());
        assertNull(message.output());
    }

    @Test
    void terminalEventCreatesMessageAndAttemptWithoutAcceptedRow() {
        when(taskDetailStore.getTaskMessageProjection("task-001", "msg-001"))
                .thenReturn(Optional.empty());
        TaskDetailStoreReviewMaterializer materializer = new TaskDetailStoreReviewMaterializer(taskDetailStore);

        Instant assigned = Instant.parse("2026-05-29T02:01:00Z");
        Instant started = Instant.parse("2026-05-29T02:01:05Z");
        Instant completed = Instant.parse("2026-05-29T02:01:12Z");
        materializer.apply(new TaskReviewWorkTerminalEvent(
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
                assigned,
                started,
                completed,
                completed,
                Map.of("ok", true)));

        ArgumentCaptor<TaskDetailStore.TaskMessageProjection> messageCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageProjection.class);
        verify(taskDetailStore).upsertTaskMessageProjection(eq("task-001"), messageCaptor.capture());
        TaskDetailStore.TaskMessageProjection message = messageCaptor.getValue();
        assertEquals(TaskMessageProjectionStatus.SUCCESS, message.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, message.finalReason());
        assertEquals(Map.of("eventCode", "probe.weather"), message.input());
        assertEquals("payload://result/msg-001", message.payloadRef());
        assertEquals(asLocalDateTime(assigned), message.assignedTime());
        assertEquals(asLocalDateTime(started), message.startTime());
        assertEquals(asLocalDateTime(completed), message.completeTime());
        assertEquals("attempt-002", message.latestAttemptId());
        assertEquals("worker-001", message.latestAttemptWorkerId());
        assertEquals("batch-001", message.latestAttemptBatchId());

        ArgumentCaptor<TaskDetailStore.TaskMessageAttemptProjection> attemptCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageAttemptProjection.class);
        verify(taskDetailStore).upsertTaskMessageAttemptProjection(
                eq("task-001"),
                eq("msg-001"),
                attemptCaptor.capture());
        TaskDetailStore.TaskMessageAttemptProjection attempt = attemptCaptor.getValue();
        assertEquals("attempt-002", attempt.attemptId());
        assertEquals(2, attempt.attemptNo());
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, attempt.status());
        assertEquals(TaskMessageAttemptProjectionFinalReason.SUCCESS, attempt.finalReason());
    }

    @Test
    void acceptedEventAfterTerminalDoesNotEraseFinalFields() {
        TaskDetailStore.TaskMessageProjection terminal = terminalProjection();
        when(taskDetailStore.getTaskMessageProjection("task-001", "msg-001"))
                .thenReturn(Optional.of(terminal));
        TaskDetailStoreReviewMaterializer materializer = new TaskDetailStoreReviewMaterializer(taskDetailStore);

        materializer.apply(new TaskReviewItemsAcceptedEvent(
                "task-001",
                List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                List.of("msg-001"),
                1,
                3));

        ArgumentCaptor<TaskDetailStore.TaskMessageProjection> messageCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageProjection.class);
        verify(taskDetailStore).upsertTaskMessageProjection(eq("task-001"), messageCaptor.capture());
        TaskDetailStore.TaskMessageProjection message = messageCaptor.getValue();
        assertEquals(Map.of("eventCode", "probe.weather", "city", "shenzhen"), message.input());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, message.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, message.finalReason());
        assertEquals("attempt-002", message.latestAttemptId());
        assertEquals("worker-001", message.latestAttemptWorkerId());
        assertEquals("batch-001", message.latestAttemptBatchId());
        assertEquals(Map.of("ok", true), message.output());
    }

    @Test
    void repeatedTerminalEventsUpsertSameMessageAndAttemptKeys() {
        TaskDetailStore.TaskMessageProjection terminal = terminalProjection();
        when(taskDetailStore.getTaskMessageProjection("task-001", "msg-001"))
                .thenReturn(Optional.empty(), Optional.of(terminal));
        TaskDetailStoreReviewMaterializer materializer = new TaskDetailStoreReviewMaterializer(taskDetailStore);
        TaskReviewWorkTerminalEvent event = new TaskReviewWorkTerminalEvent(
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
                Map.of("ok", true));

        materializer.apply(event);
        materializer.apply(event);

        ArgumentCaptor<TaskDetailStore.TaskMessageProjection> messageCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageProjection.class);
        verify(taskDetailStore, times(2)).upsertTaskMessageProjection(eq("task-001"), messageCaptor.capture());
        assertEquals("msg-001", messageCaptor.getAllValues().get(0).messageId());
        assertEquals("msg-001", messageCaptor.getAllValues().get(1).messageId());
        ArgumentCaptor<TaskDetailStore.TaskMessageAttemptProjection> attemptCaptor =
                ArgumentCaptor.forClass(TaskDetailStore.TaskMessageAttemptProjection.class);
        verify(taskDetailStore, times(2)).upsertTaskMessageAttemptProjection(
                eq("task-001"),
                eq("msg-001"),
                attemptCaptor.capture());
        assertEquals("attempt-002", attemptCaptor.getAllValues().get(0).attemptId());
        assertEquals("attempt-002", attemptCaptor.getAllValues().get(1).attemptId());
    }

    private static TaskDetailStore.TaskMessageProjection terminalProjection() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 29, 10, 0);
        return new TaskDetailStore.TaskMessageProjection(
                "msg-001",
                "task-001",
                Map.of("eventCode", "probe.weather"),
                "payload://result/msg-001",
                TaskMessageProjectionStatus.SUCCESS,
                base.plusSeconds(1),
                base,
                base.plusSeconds(12),
                base.plusSeconds(5),
                base.plusSeconds(12),
                1,
                3,
                null,
                null,
                TaskMessageProjectionFinalReason.BUSINESS_SUCCESS,
                Map.of("ok", true),
                "attempt-002",
                "worker-001",
                "batch-001");
    }

    private static LocalDateTime asLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
