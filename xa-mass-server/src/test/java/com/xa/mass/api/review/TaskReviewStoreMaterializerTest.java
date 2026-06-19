package com.xa.mass.api.review;

import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskReviewStoreMaterializerTest {

    @Test
    void acceptedEventWritesInitReviewItem() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

        materializer.apply(new TaskReviewItemsAcceptedEvent(
                "task-001",
                List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                List.of("msg-001"),
                1,
                3));

        TaskReviewItem item = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("msg-001", item.messageId());
        assertEquals("probe.weather", item.eventCode());
        assertEquals(Map.of("eventCode", "probe.weather", "city", "shenzhen"), item.input());
        assertEquals("INIT", item.status());
        assertEquals(0, item.retryCount());
        assertEquals(3, item.maxRetryCount());
        assertNull(item.finalReason());
        assertNull(item.output());
    }

    @Test
    void terminalEventCreatesMessageAndAttemptWithoutAcceptedRow() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

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

        TaskReviewItem item = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("SUCCESS", item.status());
        assertEquals("BUSINESS_SUCCESS", item.finalReason());
        assertEquals(Map.of("eventCode", "probe.weather"), item.input());
        assertEquals("payload://result/msg-001", item.payloadRef());
        assertEquals(asLocalDateTime(assigned), item.assignedTime());
        assertEquals(asLocalDateTime(started), item.startTime());
        assertEquals(asLocalDateTime(completed), item.completeTime());
        assertEquals("attempt-002", item.attemptId());
        assertEquals("worker-001", item.workerId());
        assertEquals("batch-001", item.batchId());

        TaskReviewAttempt attempt = store.listAttempts("task-001", "msg-001").get(0);
        assertEquals("attempt-002", attempt.attemptId());
        assertEquals(2, attempt.attemptNo());
        assertEquals("SUCCEEDED", attempt.status());
        assertEquals("SUCCESS", attempt.finalReason());
    }

    @Test
    void terminalEventExpandsJsonObjectResultForReviewOutputOnly() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

        materializer.apply(new TaskReviewWorkTerminalEvent(
                "task-001",
                "msg-001",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                0,
                3,
                "probe.weather",
                "worker-001",
                "batch-001",
                "attempt-001",
                null,
                null,
                "payload://result/msg-001",
                Instant.parse("2026-05-29T02:00:00Z"),
                Instant.parse("2026-05-29T02:01:00Z"),
                Instant.parse("2026-05-29T02:01:05Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Map.of("result",
                        "{\"integrationProbe\":\"cross-language-node\",\"workerProfile\":{\"workerId\":\"worker-001\"}}")));

        TaskReviewItem item = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("cross-language-node", item.output().get("integrationProbe"));
        @SuppressWarnings("unchecked")
        Map<String, Object> workerProfile = (Map<String, Object>) item.output().get("workerProfile");
        assertEquals("worker-001", workerProfile.get("workerId"));

        TaskReviewAttempt attempt = store.listAttempts("task-001", "msg-001").get(0);
        assertEquals("cross-language-node", attempt.output().get("integrationProbe"));
    }

    @Test
    void terminalFailureExpandsJsonObjectErrorMessageForReviewOutputOnly() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

        materializer.apply(new TaskReviewWorkTerminalEvent(
                "task-001",
                "msg-001",
                "FAILED",
                "BUSINESS_FAILURE",
                0,
                3,
                "stock.quote.fetch",
                "worker-001",
                "batch-001",
                "attempt-001",
                "INVALID_INPUT",
                "{\"detail\":\"requestId and symbol are required\",\"requestId\":\"stockreq-invalid-0003\"}",
                "payload://result/msg-001",
                Instant.parse("2026-05-29T02:00:00Z"),
                Instant.parse("2026-05-29T02:01:00Z"),
                Instant.parse("2026-05-29T02:01:05Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Map.of()));

        TaskReviewItem item = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("stockreq-invalid-0003", item.output().get("requestId"));
        assertEquals("INVALID_INPUT", item.errorCode());

        TaskReviewAttempt attempt = store.listAttempts("task-001", "msg-001").get(0);
        assertEquals("stockreq-invalid-0003", attempt.output().get("requestId"));
    }

    @Test
    void retryableAttemptClosedIsMaterializedBeforeLaterTerminalSuccess() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

        materializer.apply(new TaskReviewItemsAcceptedEvent(
                "task-001",
                List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                List.of("msg-001"),
                1,
                3));
        materializer.apply(new TaskReviewAttemptClosedEvent(
                "task-001",
                "msg-001",
                "attempt-001",
                1,
                "worker-stale",
                "batch-001",
                "EXPIRED",
                "LEASE_EXPIRED"));

        TaskReviewItem expiredItem = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("EXPIRED", expiredItem.status());
        assertEquals("LEASE_EXPIRED", expiredItem.finalReason());
        assertEquals("attempt-001", expiredItem.attemptId());
        assertEquals("worker-stale", expiredItem.workerId());

        materializer.apply(new TaskReviewWorkTerminalEvent(
                "task-001",
                "msg-001",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                1,
                3,
                "probe.weather",
                "worker-steady",
                "batch-002",
                "attempt-002",
                null,
                null,
                "payload://result/msg-001",
                Instant.parse("2026-05-29T02:00:00Z"),
                Instant.parse("2026-05-29T02:01:00Z"),
                Instant.parse("2026-05-29T02:01:05Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Instant.parse("2026-05-29T02:01:12Z"),
                Map.of("ok", true)));

        TaskReviewItem terminalItem = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals("SUCCESS", terminalItem.status());
        assertEquals("BUSINESS_SUCCESS", terminalItem.finalReason());
        assertEquals("attempt-002", terminalItem.attemptId());
        assertEquals("worker-steady", terminalItem.workerId());

        List<TaskReviewAttempt> attempts = store.listAttempts("task-001", "msg-001");
        assertEquals(2, attempts.size());
        assertEquals("attempt-001", attempts.get(0).attemptId());
        assertEquals("EXPIRED", attempts.get(0).status());
        assertEquals("LEASE_EXPIRED", attempts.get(0).finalReason());
        assertEquals("attempt-002", attempts.get(1).attemptId());
        assertEquals("SUCCEEDED", attempts.get(1).status());
        assertEquals("SUCCESS", attempts.get(1).finalReason());
    }

    @Test
    void acceptedEventAfterTerminalDoesNotEraseFinalFields() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        store.upsertItem("task-001", terminalItem());
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);

        materializer.apply(new TaskReviewItemsAcceptedEvent(
                "task-001",
                List.of(Map.of("eventCode", "probe.weather", "city", "shenzhen")),
                List.of("msg-001"),
                1,
                3));

        TaskReviewItem item = store.findItem("task-001", "msg-001").orElseThrow();
        assertEquals(Map.of("eventCode", "probe.weather", "city", "shenzhen"), item.input());
        assertEquals("SUCCESS", item.status());
        assertEquals("BUSINESS_SUCCESS", item.finalReason());
        assertEquals("attempt-002", item.attemptId());
        assertEquals("worker-001", item.workerId());
        assertEquals("batch-001", item.batchId());
        assertEquals(Map.of("ok", true), item.output());
    }

    @Test
    void repeatedTerminalEventsUpsertSameMessageAndAttemptKeys() {
        InMemoryTaskReviewStore store = new InMemoryTaskReviewStore();
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(store);
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

        assertEquals(1, store.listItems("task-001", 10).size());
        assertEquals(1, store.listAttempts("task-001", "msg-001").size());
        assertEquals("attempt-002", store.listAttempts("task-001", "msg-001").get(0).attemptId());
    }

    private static TaskReviewItem terminalItem() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 29, 10, 0);
        return new TaskReviewItem(
                "msg-001",
                "probe.weather",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                "payload://result/msg-001",
                1,
                3,
                base,
                base.plusSeconds(1),
                base.plusSeconds(5),
                base.plusSeconds(12),
                base.plusSeconds(12),
                Map.of("eventCode", "probe.weather"),
                "worker-001",
                "batch-001",
                "attempt-002",
                null,
                null,
                Map.of("ok", true));
    }

    private static LocalDateTime asLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
