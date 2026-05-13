package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.storage.api.TaskDetailStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/review/tasks")
public class InternalTaskReviewController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TASK_REVIEW_PREVIEW_LIMIT = Integer.getInteger("xa.mass.api.taskReviewPreviewLimit", 12);
    private static final int TASK_REVIEW_EXPORT_LIMIT = Integer.getInteger("xa.mass.api.taskReviewExportLimit", 20000);
    private static final com.fasterxml.jackson.databind.ObjectMapper RESPONSE_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final TaskQueryOperations taskQueries;
    private final TaskDetailStore taskDetailStore;

    public InternalTaskReviewController(TaskQueryOperations taskQueries,
                                        TaskDetailStore taskDetailStore) {
        this.taskQueries = taskQueries;
        this.taskDetailStore = taskDetailStore;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskReview(@PathVariable String taskId) {
        return executeApi("Task review lookup failed", () -> {
            requireTaskDetail(taskId);
            TaskDetailStore.TaskMessageStats stats = taskDetailStore.getTaskMessageStats(taskId);
            List<TaskDetailStore.TaskMessageProjection> preview = loadTaskMessageProjections(taskId, TASK_REVIEW_PREVIEW_LIMIT);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("summary", toTaskReviewSummary(stats, preview.size()));
            response.put("seedPreview", preview.stream().map(this::toTaskSeedPreviewItem).toList());
            response.put("resultPreview", preview.stream().map(this::toTaskResultPreviewItem).toList());
            response.put("exports", Map.of(
                    "seedUrl", "/internal/v1/review/tasks/" + taskId + "/seed-export",
                    "resultUrl", "/internal/v1/review/tasks/" + taskId + "/result-export"
            ));
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @GetMapping("/{taskId}/seed-export")
    public ResponseEntity<?> exportTaskSeeds(@PathVariable String taskId) {
        return exportTaskReviewPayload(taskId, "seed", true);
    }

    @GetMapping("/{taskId}/result-export")
    public ResponseEntity<?> exportTaskResults(@PathVariable String taskId) {
        return exportTaskReviewPayload(taskId, "result", false);
    }

    private ResponseEntity<?> exportTaskReviewPayload(String taskId,
                                                      String exportKind,
                                                      boolean exportSeedRows) {
        return executeRawApi("Task " + exportKind + " export failed", () -> {
            TaskDetailSnapshot task = requireTaskDetail(taskId);
            int exportLimit = resolveReviewExportLimit(task);
            List<TaskDetailStore.TaskMessageProjection> projections = loadTaskMessageProjections(taskId, exportLimit);
            List<Map<String, Object>> rows = projections.stream()
                    .map(exportSeedRows ? this::toTaskSeedPreviewItem : this::toTaskResultPreviewItem)
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("taskName", task.getTaskName());
            payload.put("project", task.getProject());
            payload.put("exportKind", exportKind);
            payload.put("exportedAt", formatDateTime(LocalDateTime.now()));
            payload.put("rowCount", rows.size());
            payload.put("rows", rows);
            byte[] body = RESPONSE_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + buildTaskExportFileName(task, exportKind) + "\"")
                    .body(body);
        });
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> executeApi(String failurePrefix,
                                                                        ApiResponseSupplier action) {
        try {
            return action.execute();
        } catch (TaskReviewException e) {
            return ResponseEntity.status(e.statusCode()).body(ApiResponse.error(e.statusCode(), e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, failurePrefix + ": " + e.getMessage()));
        }
    }

    private ResponseEntity<?> executeRawApi(String failurePrefix, RawResponseSupplier action) {
        try {
            return action.execute();
        } catch (TaskReviewException e) {
            return ResponseEntity.status(e.statusCode()).body(ApiResponse.error(e.statusCode(), e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, failurePrefix + ": " + e.getMessage()));
        }
    }

    private TaskDetailSnapshot requireTaskDetail(String taskId) {
        ensureTaskDetailStoreConfigured();
        TaskDetailSnapshot task = taskQueries.getTaskDetail(taskId);
        if (task == null) {
            throw new TaskReviewException(404, "Task not found: " + taskId);
        }
        return task;
    }

    private void ensureTaskDetailStoreConfigured() {
        if (taskDetailStore == null) {
            throw new TaskReviewException(400, "Task review storage is unavailable");
        }
    }

    private List<TaskDetailStore.TaskMessageProjection> loadTaskMessageProjections(String taskId, int limit) {
        List<TaskDetailStore.TaskMessageProjection> projections =
                taskDetailStore.getTaskMessageProjections(taskId, Math.max(1, limit));
        return projections.stream()
                .sorted(Comparator.comparing(TaskDetailStore.TaskMessageProjection::createTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Map<String, Object> toTaskReviewSummary(TaskDetailStore.TaskMessageStats stats, int previewCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalItems", stats != null ? stats.getTotal() : 0L);
        summary.put("successItems", stats != null ? stats.getSuccess() : 0L);
        summary.put("failedItems", stats != null ? stats.getFailed() : 0L);
        summary.put("expiredItems", stats != null ? stats.getExpired() : 0L);
        summary.put("processingItems", stats != null ? stats.getProcessing() : 0L);
        summary.put("previewCount", previewCount);
        summary.put("previewLimit", TASK_REVIEW_PREVIEW_LIMIT);
        summary.put("hasMore", stats != null && stats.getTotal() > previewCount);
        return summary;
    }

    private Map<String, Object> toTaskSeedPreviewItem(TaskDetailStore.TaskMessageProjection projection) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("messageId", projection.messageId());
        item.put("eventCode", resolveProjectionEventCode(projection));
        item.put("status", enumName(projection.status()));
        item.put("payloadRef", projection.payloadRef());
        item.put("retryCount", projection.retryCount());
        item.put("maxRetryCount", projection.maxRetryCount());
        item.put("createTime", formatDateTime(projection.createTime()));
        item.put("assignedTime", formatDateTime(projection.assignedTime()));
        item.put("input", projection.input());
        return item;
    }

    private Map<String, Object> toTaskResultPreviewItem(TaskDetailStore.TaskMessageProjection projection) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("messageId", projection.messageId());
        item.put("eventCode", resolveProjectionEventCode(projection));
        item.put("status", enumName(projection.status()));
        item.put("finalReason", enumName(projection.finalReason()));
        item.put("retryCount", projection.retryCount());
        item.put("maxRetryCount", projection.maxRetryCount());
        item.put("workerId", projection.latestAttemptWorkerId());
        item.put("workerContextId", projection.latestAttemptWorkerContextId());
        item.put("batchId", projection.latestAttemptBatchId());
        item.put("attemptId", projection.latestAttemptId());
        item.put("startTime", formatDateTime(projection.startTime()));
        item.put("completeTime", formatDateTime(projection.completeTime()));
        item.put("updateTime", formatDateTime(projection.updateTime()));
        item.put("errorCode", projection.errorCode());
        item.put("errorMessage", projection.errorMessage());
        item.put("output", projection.output());
        return item;
    }

    private String resolveProjectionEventCode(TaskDetailStore.TaskMessageProjection projection) {
        if (projection == null || projection.input() == null) {
            return null;
        }
        Object rawValue = projection.input().get("eventCode");
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    private int resolveReviewExportLimit(TaskDetailSnapshot task) {
        int requested = task != null ? Math.max(task.getTaskTargetNumber(), task.getTaskEligibleNumber()) : 0;
        if (requested <= 0 && taskDetailStore != null) {
            TaskDetailStore.TaskMessageStats stats = taskDetailStore.getTaskMessageStats(task.getTaskId());
            requested = stats != null ? Math.toIntExact(Math.min(Integer.MAX_VALUE, stats.getTotal())) : 0;
        }
        if (requested <= 0) {
            return TASK_REVIEW_PREVIEW_LIMIT;
        }
        return Math.min(requested, TASK_REVIEW_EXPORT_LIMIT);
    }

    private String buildTaskExportFileName(TaskDetailSnapshot task, String exportKind) {
        String taskName = task != null && task.getTaskName() != null ? task.getTaskName().trim() : "task";
        String normalizedTaskName = taskName.replaceAll("[^a-zA-Z0-9._-]+", "-");
        if (normalizedTaskName.isBlank()) {
            normalizedTaskName = "task";
        }
        return normalizedTaskName + "-" + exportKind + "-" + task.getTaskId() + ".json";
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    @FunctionalInterface
    private interface ApiResponseSupplier {
        ResponseEntity<ApiResponse<Map<String, Object>>> execute() throws Exception;
    }

    @FunctionalInterface
    private interface RawResponseSupplier {
        ResponseEntity<?> execute() throws Exception;
    }

    private static final class TaskReviewException extends RuntimeException {
        private final int statusCode;

        private TaskReviewException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
