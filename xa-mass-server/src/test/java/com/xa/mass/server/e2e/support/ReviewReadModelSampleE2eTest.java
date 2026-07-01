package com.xa.mass.server.e2e.support;

import com.xa.mass.api.review.TaskReviewReadModel;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Explicit server review-read-model E2E support.
 *
 * <p>Mainline scheduling/lifecycle suites should stay on
 * {@link AbstractSampleE2eTest}'s runtime-first helpers. Extend this class only
 * when the test intentionally proves console/review read-model behavior.
 */
public abstract class ReviewReadModelSampleE2eTest extends AbstractSampleE2eTest {

    @Autowired
    protected TaskReviewReadModel taskReviewReadModel;

    protected TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskSnapshot(taskId, expectedStatus, 20, 250L);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId,
                                               String expectedStatus,
                                               int maxAttempts,
                                               long sleepMillis) throws InterruptedException {
        return waitForTaskSnapshot(taskId,
                snapshot -> expectedStatus.equals(snapshot.task().get("status")),
                expectedStatus,
                maxAttempts,
                sleepMillis);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId,
                                               Predicate<TaskSnapshot> condition,
                                               String expectation,
                                               int maxAttempts,
                                               long sleepMillis) throws InterruptedException {
        return awaitValue(
                "Task " + taskId + " did not reach expected review state: " + expectation,
                maxAttempts,
                sleepMillis,
                () -> fetchTaskSnapshot(taskId),
                condition,
                latestSnapshot -> "status=" + (latestSnapshot == null ? "<none>" : latestSnapshot.task().get("status"))
                        + ", messages=" + (latestSnapshot == null ? 0 : latestSnapshot.messages().size())
        );
    }

    protected TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        return waitForTaskSnapshot(taskId, "TERMINAL");
    }

    protected TaskSnapshot fetchTaskSnapshot(String taskId) {
        Map<String, Object> detailResponse = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> task = task(detailResponse);
        return new TaskSnapshot(task, fetchCompatibilityMessages(taskId, task, 500));
    }

    protected List<Map<String, Object>> fetchTaskMessageAttempts(String taskId, String messageId) {
        List<Map<String, Object>> attempts = new java.util.ArrayList<>();
        for (TaskReviewAttempt projection : taskReviewReadModel.loadAttempts(taskId, messageId)) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attemptId", projection.attemptId());
            attempt.put("taskId", projection.taskId());
            attempt.put("messageId", projection.messageId());
            attempt.put("attemptNo", projection.attemptNo());
            attempt.put("workerId", projection.workerId());
            attempt.put("batchId", projection.batchId());
            attempt.put("status", projection.status());
            attempt.put("leaseExpireTime", null);
            attempt.put("dispatchTime", null);
            attempt.put("ackTime", null);
            attempt.put("startTime", null);
            attempt.put("finishTime", null);
            attempt.put("finalReason", projection.finalReason());
            attempt.put("errorMessage", projection.errorMessage());
            attempt.put("errorCode", projection.errorCode());
            attempt.put("output", projection.output() == null ? null : new LinkedHashMap<>(projection.output()));
            attempt.put("createTime", null);
            attempt.put("updateTime", null);
            attempts.add(attempt);
        }
        return attempts;
    }

    private List<Map<String, Object>> fetchCompatibilityMessages(String taskId,
                                                                 Map<String, Object> taskView,
                                                                 int limit) {
        Map<String, TaskActiveLeaseSnapshot> activeLeaseByMessageId = new LinkedHashMap<>();
        for (TaskActiveLeaseSnapshot activeLease : app.taskDiagnostics().getActiveLeases(taskId)) {
            if (activeLease != null
                    && activeLease.messageId() != null
                    && !activeLease.messageId().isBlank()) {
                activeLeaseByMessageId.put(activeLease.messageId(), activeLease);
            }
        }
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        Set<String> seenMessageIds = new java.util.LinkedHashSet<>();
        for (TaskReviewItem projection : taskReviewReadModel.loadItems(taskId, limit)) {
            TaskActiveLeaseSnapshot activeLease = activeLeaseByMessageId.get(projection.messageId());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", projection.messageId());
            message.put("taskId", taskId);
            message.put("status", overlayStatus(taskView, projection, activeLease));
            message.put("latestAttemptWorkerId", activeLease != null ? activeLease.workerId() : projection.workerId());
            message.put("latestAttemptBatchId", activeLease != null ? activeLease.batchId() : projection.batchId());
            message.put("retryCount", activeLease != null ? Math.max(0, activeLease.retryCount()) : projection.retryCount());
            message.put("maxRetryCount", projection.maxRetryCount());
            message.put("errorMessage", projection.errorMessage());
            message.put("errorCode", projection.errorCode());
            message.put("finalReason", overlayFinalReason(taskView, projection));
            message.put("payloadRef", activeLease != null && activeLease.payloadRef() != null && !activeLease.payloadRef().isBlank()
                    ? activeLease.payloadRef()
                    : projection.payloadRef());
            message.put("input", projection.input() == null ? null : new LinkedHashMap<>(projection.input()));
            message.put("output", projection.output() == null ? null : new LinkedHashMap<>(projection.output()));
            message.put("result", projection.output() == null ? null : new LinkedHashMap<>(projection.output()));
            message.put("assignedTime", projection.assignedTime() != null
                    ? projection.assignedTime()
                    : activeLease != null ? activeLease.leasedAt() : null);
            message.put("createTime", projection.createTime());
            message.put("updateTime", projection.updateTime());
            message.put("startTime", projection.startTime());
            message.put("completeTime", projection.completeTime());
            messages.add(message);
            seenMessageIds.add(projection.messageId());
        }
        for (TaskActiveLeaseSnapshot activeLease : activeLeaseByMessageId.values()) {
            if (activeLease == null
                    || seenMessageIds.contains(activeLease.messageId())) {
                continue;
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", activeLease.messageId());
            message.put("taskId", activeLease.taskId());
            message.put("status", "ASSIGNED");
            message.put("latestAttemptWorkerId", activeLease.workerId());
            message.put("latestAttemptBatchId", activeLease.batchId());
            message.put("retryCount", Math.max(0, activeLease.retryCount()));
            message.put("maxRetryCount", 0);
            message.put("errorMessage", null);
            message.put("errorCode", null);
            message.put("finalReason", overlayFinalReason(taskView, null));
            message.put("payloadRef", activeLease.payloadRef());
            message.put("input", null);
            message.put("output", null);
            message.put("result", null);
            message.put("assignedTime", activeLease.leasedAt());
            message.put("createTime", null);
            message.put("updateTime", null);
            message.put("startTime", null);
            message.put("completeTime", null);
            messages.add(message);
        }
        return messages;
    }

    private static String overlayStatus(Map<String, Object> taskView,
                                        TaskReviewItem projection,
                                        TaskActiveLeaseSnapshot activeLease) {
        String baseStatus = projection != null ? projection.status() : null;
        if (isFinalStatus(baseStatus)) {
            return baseStatus;
        }
        if (isTerminalStop(taskView)) {
            return isProcessingStatus(baseStatus) || activeLease != null ? "EXPIRED" : "FAILED";
        }
        if (activeLease != null) {
            return "ASSIGNED";
        }
        return baseStatus;
    }

    private static String overlayFinalReason(Map<String, Object> taskView,
                                             TaskReviewItem projection) {
        if (!isTerminalStop(taskView)) {
            return projection != null ? projection.finalReason() : null;
        }
        return "MANUAL_CANCELLED";
    }

    private static boolean isFinalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }

    private static boolean isTerminalStop(Map<String, Object> taskView) {
        if (taskView == null) {
            return false;
        }
        Object status = taskView.get("status");
        if (!"TERMINAL".equals(status)) {
            return false;
        }
        Object terminalReason = taskView.get("terminalReason");
        return "MANUAL_CANCELLED".equals(terminalReason)
                || "MAX_RUNTIME_REACHED".equals(terminalReason)
                || "SUCCESS_RATE_REACHED".equals(terminalReason)
                || "FAILURE_RATE_REACHED".equals(terminalReason);
    }

    private static boolean isProcessingStatus(String status) {
        return "ASSIGNED".equals(status) || "RUNNING".equals(status);
    }

    protected record TaskSnapshot(Map<String, Object> task, List<Map<String, Object>> messages) {
    }
}
