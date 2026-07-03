package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.review.TaskReviewReadModel;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewSnapshot;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewStats;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
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
    private final TaskReviewReadModel taskReviewReadModel;

    public InternalTaskReviewController(TaskQueryOperations taskQueries,
                                        TaskReviewReadModel taskReviewReadModel) {
        this.taskQueries = taskQueries;
        this.taskReviewReadModel = taskReviewReadModel;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskReview(@PathVariable String taskId) {
        return executeApi("Task review lookup failed", () -> {
            requireTaskDetail(taskId);
            TaskReviewSnapshot review = loadTaskReview(taskId, TASK_REVIEW_PREVIEW_LIMIT);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("summary", toTaskReviewSummary(review.stats(), review.preview().size()));
            response.put("seedPreview", review.preview().stream().map(this::toTaskSeedPreviewItem).toList());
            response.put("resultPreview", review.preview().stream().map(this::toTaskResultPreviewItem).toList());
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
            List<TaskReviewItem> items = loadTaskReviewItems(taskId, exportLimit);
            List<Map<String, Object>> rows = items.stream()
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
        ensureTaskReviewReadModelConfigured();
        TaskDetailSnapshot task = taskQueries.getTaskDetail(taskId);
        if (task == null) {
            throw new TaskReviewException(404, "Task not found: " + taskId);
        }
        return task;
    }

    private void ensureTaskReviewReadModelConfigured() {
        if (taskReviewReadModel == null) {
            throw new TaskReviewException(400, "Task review read model is unavailable");
        }
    }

    private TaskReviewSnapshot loadTaskReview(String taskId, int previewLimit) {
        ensureTaskReviewReadModelConfigured();
        return taskReviewReadModel.loadReview(taskId, Math.max(1, previewLimit));
    }

    private List<TaskReviewItem> loadTaskReviewItems(String taskId, int limit) {
        ensureTaskReviewReadModelConfigured();
        return taskReviewReadModel.loadItems(taskId, Math.max(1, limit));
    }

    private TaskReviewStats loadTaskReviewStats(String taskId) {
        ensureTaskReviewReadModelConfigured();
        return taskReviewReadModel.loadStats(taskId);
    }

    private Map<String, Object> toTaskReviewSummary(TaskReviewStats stats, int previewCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalItems", stats != null ? stats.totalItems() : 0L);
        summary.put("successItems", stats != null ? stats.successItems() : 0L);
        summary.put("failedItems", stats != null ? stats.failedItems() : 0L);
        summary.put("expiredItems", stats != null ? stats.expiredItems() : 0L);
        summary.put("processingItems", stats != null ? stats.processingItems() : 0L);
        summary.put("previewCount", previewCount);
        summary.put("previewLimit", TASK_REVIEW_PREVIEW_LIMIT);
        summary.put("hasMore", stats != null && stats.totalItems() > previewCount);
        return summary;
    }

    private Map<String, Object> toTaskSeedPreviewItem(TaskReviewItem reviewItem) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("messageId", reviewItem.messageId());
        item.put("eventCode", reviewItem.eventCode());
        item.put("status", reviewItem.status());
        item.put("payloadRef", reviewItem.payloadRef());
        item.put("retryCount", reviewItem.retryCount());
        item.put("maxRetryCount", reviewItem.maxRetryCount());
        item.put("createTime", formatDateTime(reviewItem.createTime()));
        item.put("assignedTime", formatDateTime(reviewItem.assignedTime()));
        item.put("input", reviewItem.input());
        return item;
    }

    private Map<String, Object> toTaskResultPreviewItem(TaskReviewItem reviewItem) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("messageId", reviewItem.messageId());
        item.put("eventCode", reviewItem.eventCode());
        item.put("status", reviewItem.status());
        item.put("finalReason", reviewItem.finalReason());
        item.put("retryCount", reviewItem.retryCount());
        item.put("maxRetryCount", reviewItem.maxRetryCount());
        item.put("workerId", reviewItem.workerId());
        item.put("batchId", reviewItem.batchId());
        item.put("attemptId", reviewItem.attemptId());
        item.put("startTime", formatDateTime(reviewItem.startTime()));
        item.put("completeTime", formatDateTime(reviewItem.completeTime()));
        item.put("updateTime", formatDateTime(reviewItem.updateTime()));
        item.put("errorCode", reviewItem.errorCode());
        item.put("errorMessage", reviewItem.errorMessage());
        item.put("output", reviewItem.output());
        return item;
    }

    private int resolveReviewExportLimit(TaskDetailSnapshot task) {
        int requested = task != null ? Math.max(task.getTaskTargetNumber(), task.getTaskEligibleNumber()) : 0;
        if (requested <= 0 && taskReviewReadModel != null) {
            TaskReviewStats stats = loadTaskReviewStats(task.getTaskId());
            requested = stats != null ? Math.toIntExact(Math.min(Integer.MAX_VALUE, stats.totalItems())) : 0;
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
