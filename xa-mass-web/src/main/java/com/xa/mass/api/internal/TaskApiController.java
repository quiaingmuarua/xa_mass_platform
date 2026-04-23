package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskAppendItemsApiRequest;
import com.xa.mass.api.model.task.TaskCreateApiRequest;
import com.xa.mass.api.model.task.TaskUpdateApiRequest;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/status/api/tasks")
public class TaskApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<TaskStatus> EDITABLE_TASK_STATUSES = Set.of(TaskStatus.NEW, TaskStatus.BLOCKED);

    private final TaskOperations taskOperations;

    public TaskApiController(TaskOperations taskOperations) {
        this.taskOperations = taskOperations;
    }

    @GetMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTasks(@RequestParam(required = false) String keyword,
                                                                      @RequestParam(required = false) TaskStatus status) {
        try {
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            List<Map<String, Object>> items = taskOperations.getAllTasks().stream()
                    .filter(task -> matchesKeyword(task, normalizedKeyword))
                    .filter(task -> status == null || task.getStatus() == status)
                    .sorted(Comparator
                            .comparing(Task::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Task::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::toTaskListItem)
                    .collect(Collectors.toList());
            return ok(Map.of("items", items, "total", items.size()));
        } catch (Exception e) {
            return badRequest("Task list failed: " + e.getMessage());
        }
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(@RequestBody TaskCreateApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task create");
            Task task = taskOperations.createTask(toTaskCreateRequest(requestBody));
            return ok(Map.of("taskId", task.getTid(), "message", "Task created"));
        } catch (Exception e) {
            return badRequest("Task create failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            List<Map<String, Object>> items = taskOperations.getTaskMessages(taskId).stream()
                    .map(TaskMsg::getInput)
                    .map(input -> input == null ? Map.<String, Object>of() : new LinkedHashMap<>(input))
                    .collect(Collectors.toList());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", task);
            response.put("items", items);
            response.put("stateValidation", taskOperations.validateTaskState(taskId));
            return ok(response);
        } catch (Exception e) {
            return badRequest("Task lookup failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTaskStatus(@PathVariable String taskId,
                                                                             @RequestParam TaskStatus status) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            boolean success = switch (status) {
                case READY -> task.getStatus() == TaskStatus.PAUSED
                        ? taskOperations.resumeTaskDetailed(taskId).success()
                        : taskOperations.approveTask(taskId);
                case BLOCKED -> blockTask(task);
                case PAUSED -> taskOperations.pauseTask(taskId);
                case TERMINAL -> taskOperations.cancelTask(taskId);
                default -> false;
            };

            Task updatedTask = taskOperations.getTask(taskId);
            if (success && updatedTask != null) {
                return ok(Map.of("message", "Task status updated", "newStatus", updatedTask.getStatus().name()));
            }
            return conflict("Task cannot transition to status " + status.name());
        } catch (Exception e) {
            return badRequest("Task status update failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/block")
    public ResponseEntity<ApiResponse<Map<String, Object>>> blockTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (blockTask(task)) {
                return ok(Map.of("message", "Task blocked"));
            }
            return conflict("Task cannot be blocked from the current state");
        } catch (Exception e) {
            return badRequest("Task block failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/audit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> auditTask(@PathVariable String taskId,
                                                                      @RequestParam String approved,
                                                                      @RequestParam(required = false) String comment) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            boolean isApproved = "true".equalsIgnoreCase(approved);
            boolean success = isApproved ? taskOperations.approveTask(taskId) : taskOperations.rejectTask(taskId);
            Task updatedTask = taskOperations.getTask(taskId);
            if (success && updatedTask != null) {
                return ok(Map.of(
                        "message", isApproved ? "Task approved" : "Task rejected",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }
            return badRequest("Task cannot be audited from the current state");
        } catch (Exception e) {
            return badRequest("Task audit failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pauseTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (taskOperations.pauseTask(taskId)) {
                return ok(Map.of("message", "Task paused"));
            }
            return conflict("Task cannot be paused from the current state");
        } catch (Exception e) {
            return badRequest("Task pause failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumeTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            SdkTaskResumeResult result = taskOperations.resumeTaskDetailed(taskId);
            if (result.success()) {
                String message = result.completedToTerminal()
                        ? "Task already completed while paused and was closed to TERMINAL"
                        : "Task resumed";
                return ok(Map.of(
                        "message", message,
                        "newStatus", result.status(),
                        "terminalReason", result.terminalReason() != null ? result.terminalReason() : ""
                ));
            }
            return conflict("Task cannot be resumed from the current state");
        } catch (Exception e) {
            return badRequest("Task resume failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/terminate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> terminateTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (taskOperations.cancelTask(taskId)) {
                return ok(Map.of("message", "Task terminated"));
            }
            return conflict("Task cannot be terminated from the current state");
        } catch (Exception e) {
            return badRequest("Task terminate failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@PathVariable String taskId) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            boolean deleted = taskOperations.deleteTask(taskId);
            if (deleted) {
                return ok(Map.of("message", "Task deleted"));
            }
            return badRequest("Task delete failed: current status " + task.getStatus().name() + " cannot be deleted");
        } catch (Exception e) {
            return badRequest("Task delete failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTask(@PathVariable String taskId,
                                                                       @RequestBody TaskUpdateApiRequest requestBody) {
        try {
            Task task = taskOperations.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (!EDITABLE_TASK_STATUSES.contains(task.getStatus())) {
                return badRequest("Task update failed: Only NEW or BLOCKED tasks can be updated");
            }

            validateKnownFields(requestBody, "task update");
            MassTaskCreateRequest request = toTaskUpdateRequest(requestBody);
            if (request.getTaskName() != null) {
                task.setTaskName(request.getTaskName());
            }
            if (request.getProject() != null) {
                task.setProject(request.getProject());
            }
            if (request.getSharedConfig() != null) {
                task.setSharedConfig(request.getSharedConfig());
            }
            if (request.getUserId() != null) {
                task.setUser(UserRef.of(request.getUserId()));
            }
            if (request.getBatchSize() > 0) {
                task.setBatchSize(request.getBatchSize());
            }
            taskOperations.updateTask(task);
            return ok(Map.of("message", "Task updated"));
        } catch (Exception e) {
            return badRequest("Task update failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> appendTaskItems(@PathVariable String taskId,
                                                                            @RequestBody TaskAppendItemsApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task append items");
            List<Map<String, Object>> inputs = requestBody.getInputs();
            if (inputs == null || inputs.isEmpty()) {
                return badRequest("inputs must be a non-empty list");
            }
            int added = taskOperations.appendTaskItems(taskId, inputs);
            return ok(Map.of("message", "Items appended", "added", added));
        } catch (IllegalArgumentException e) {
            return notFound("Task not found: " + taskId);
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            return badRequest("Append items failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}/seal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sealTask(@PathVariable String taskId) {
        try {
            boolean sealed = taskOperations.sealTask(taskId);
            if (sealed) {
                Task task = taskOperations.getTask(taskId);
                return ok(Map.of("message", "Task sealed", "status", task != null ? task.getStatus().name() : ""));
            }
            return conflict("Task not found or not open-ended");
        } catch (Exception e) {
            return badRequest("Seal task failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskMessages(@PathVariable String taskId,
                                                                            @RequestParam(defaultValue = "1") int page,
                                                                            @RequestParam(defaultValue = "20") int size) {
        if (size > 500) {
            size = 500;
        }
        List<TaskMsg> all = taskOperations.getTaskMessages(taskId);
        int total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(from + size, total);
        List<TaskMsg> pageList = from < to ? all.subList(from, to) : Collections.emptyList();
        List<Map<String, Object>> messages = pageList.stream()
                .map(this::toTaskMessageView)
                .collect(Collectors.toList());
        return ok(Map.of("total", total, "page", page, "size", size, "messages", messages));
    }

    private Map<String, Object> toTaskMessageView(TaskMsg taskMsg) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("msgId", taskMsg.getMsgId());
        view.put("taskId", taskMsg.getTaskId());
        view.put("status", taskMsg.getStatus() != null ? taskMsg.getStatus().name() : null);
        view.put("latestAttemptWorkerId", taskMsg.getLatestAttemptWorkerId());
        view.put("latestAttemptWorkerContextId", taskMsg.getLatestAttemptWorkerContextId());
        view.put("latestAttemptBatchId", taskMsg.getLatestAttemptBatchId());
        view.put("retryCount", taskMsg.getRetryCount());
        view.put("maxRetryCount", taskMsg.getMaxRetryCount());
        view.put("result", taskMsg.getResult());
        view.put("errorMessage", taskMsg.getErrorMessage());
        view.put("errorCode", taskMsg.getErrorCode());
        view.put("finalReason", taskMsg.getFinalReason() != null ? taskMsg.getFinalReason().name() : null);
        view.put("assignedTime", taskMsg.getAssignedTime());
        view.put("createTime", taskMsg.getCreateTime());
        view.put("updateTime", taskMsg.getUpdateTime());
        view.put("startTime", taskMsg.getStartTime());
        view.put("completeTime", taskMsg.getCompleteTime());
        view.put("input", taskMsg.getInput() == null ? Map.of() : new LinkedHashMap<>(taskMsg.getInput()));
        view.put("output", taskMsg.getOutput() == null ? Map.of() : new LinkedHashMap<>(taskMsg.getOutput()));
        return view;
    }

    private Map<String, Object> toTaskListItem(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getTid());
        item.put("taskName", task.getTaskName());
        item.put("project", task.getProject());
        item.put("userId", task.getUser() != null ? task.getUser().getUserId() : null);
        item.put("status", task.getStatus() != null ? task.getStatus().name() : null);
        item.put("terminalReason", task.getTerminalReason() != null ? task.getTerminalReason().name() : null);
        item.put("successCount", task.getTaskSuccessNumber());
        item.put("eligibleCount", task.getTaskEligibleNumber());
        item.put("batchSize", task.getBatchSize());
        item.put("updatedAt", formatDateTime(task.getUpdateTime()));
        return item;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> ok(Map<String, ?> data) {
        return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>(data)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> conflict(String message) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, message));
    }

    private void validateKnownFields(TaskCreateApiRequest requestBody, String operationName) {
        if (requestBody == null || isEmptyCreateRequest(requestBody)) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateKnownFields(TaskUpdateApiRequest requestBody, String operationName) {
        if (requestBody == null || isEmptyUpdateRequest(requestBody)) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateKnownFields(TaskAppendItemsApiRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private MassTaskCreateRequest toTaskCreateRequest(TaskCreateApiRequest requestBody) {
        requireBusinessBindings(requestBody.getProject(), requestBody.getUserId());
        return MassTaskCreateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .taskName(requestBody.getTaskName())
                .sharedConfig(requestBody.getSharedConfig())
                .inputs(requestBody.getInputs())
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .openEnded(requestBody.isOpenEnded())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .build();
    }

    private MassTaskCreateRequest toTaskUpdateRequest(TaskUpdateApiRequest requestBody) {
        if (requestBody.getProject() != null) {
            ProjectRef.require(requestBody.getProject());
        }
        if (requestBody.getUserId() != null) {
            UserRef.requireUserId(requestBody.getUserId());
        }
        return MassTaskCreateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .taskName(requestBody.getTaskName())
                .sharedConfig(requestBody.getSharedConfig())
                .batchSize(requestBody.getBatchSize())
                .build();
    }

    private boolean isEmptyCreateRequest(TaskCreateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getTaskName() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getInputs() == null
                && requestBody.getBatchSize() == 0
                && requestBody.getDefaultMsgMaxRetryCount() == 3
                && !requestBody.isOpenEnded()
                && requestBody.getMaxRuntimeSeconds() == 0;
    }

    private boolean isEmptyUpdateRequest(TaskUpdateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getTaskName() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getBatchSize() == 0;
    }

    private boolean blockTask(Task task) {
        if (task == null) {
            return false;
        }
        if (task.getStatus() == TaskStatus.NEW) {
            return taskOperations.rejectTask(task.getTid());
        }
        return taskOperations.blockTask(task.getTid());
    }

    private boolean matchesKeyword(Task task, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsIgnoreCase(task.getTid(), normalizedKeyword)
                || containsIgnoreCase(task.getTaskName(), normalizedKeyword)
                || containsIgnoreCase(task.getProject(), normalizedKeyword);
    }

    private boolean containsIgnoreCase(String source, String normalizedKeyword) {
        return source != null && source.toLowerCase().contains(normalizedKeyword);
    }

    private void requireBusinessBindings(String project, String userId) {
        ProjectRef.require(project);
        UserRef.requireUserId(userId);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }
}
