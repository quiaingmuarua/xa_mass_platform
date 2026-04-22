package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskAppendItemsApiRequest;
import com.xa.mass.api.model.task.TaskCreateApiRequest;
import com.xa.mass.api.model.task.TaskUpdateApiRequest;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Collections;
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

    @Autowired
    private TaskManager taskManager;

    @GetMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTasks(@RequestParam(required = false) String keyword,
                                                                      @RequestParam(required = false) TaskStatus status) {
        try {
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            List<Map<String, Object>> items = taskManager.getAllTasks().stream()
                    .filter(task -> matchesKeyword(task, normalizedKeyword))
                    .filter(task -> status == null || task.getStatus() == status)
                    .sorted(Comparator
                            .comparing(Task::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Task::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::toTaskListItem)
                    .collect(Collectors.toList());
            return ok(Map.of(
                    "items", items,
                    "total", items.size()
            ));
        } catch (Exception e) {
            return badRequest("Task list failed: " + e.getMessage());
        }
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(@RequestBody TaskCreateApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task create");
            TaskCreateRequestDto request = toTaskCreateRequest(requestBody);
            Task task = taskManager.createTask(request);
            return ok(Map.of(
                    "taskId", task.getTid(),
                    "message", "Task created"
            ));
        } catch (Exception e) {
            return badRequest("Task create failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            List<TaskMsg> msgs = taskManager.getTaskMessages(taskId);
            List<Map<String, Object>> items = msgs.stream()
                    .map(TaskMsg::getInput)
                    .map(input -> input == null ? Map.<String, Object>of() : new LinkedHashMap<>(input))
                    .collect(Collectors.toList());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", task);
            response.put("items", items);
            response.put("stateValidation", taskManager.validateTaskState(taskId));
            return ok(response);
        } catch (Exception e) {
            return badRequest("Task lookup failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTaskStatus(@PathVariable String taskId,
                                                                             @RequestParam TaskStatus status) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            boolean success = switch (status) {
                case READY -> task.getStatus() == TaskStatus.PAUSED
                        ? taskManager.resumeTaskDetailed(taskId).isSuccess()
                        : taskManager.approveTask(taskId);
                case BLOCKED -> blockTask(task);
                case PAUSED -> taskManager.pauseTask(taskId);
                case TERMINAL -> taskManager.cancelTask(taskId);
                default -> false;
            };

            Task updatedTask = taskManager.getTask(taskId);
            if (success && updatedTask != null) {
                return ok(Map.of(
                        "message", "Task status updated",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }

            return conflict("Task cannot transition to status " + status.name());
        } catch (Exception e) {
            return badRequest("Task status update failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/block")
    public ResponseEntity<ApiResponse<Map<String, Object>>> blockTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            boolean isApproved = "true".equalsIgnoreCase(approved);
            boolean success = isApproved
                    ? taskManager.approveTask(taskId)
                    : taskManager.rejectTask(taskId);
            Task updatedTask = taskManager.getTask(taskId);

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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            if (taskManager.pauseTask(taskId)) {
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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            TaskResumeResult result = taskManager.resumeTaskDetailed(taskId);
            if (result.isSuccess()) {
                String message = result.getOutcome() == TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL
                        ? "Task already completed while paused and was closed to TERMINAL"
                        : "Task resumed";
                return ok(Map.of(
                        "message", message,
                        "newStatus", result.getStatus().name(),
                        "terminalReason", result.getTerminalReason() != null ? result.getTerminalReason().name() : ""
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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            if (taskManager.cancelTask(taskId)) {
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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            boolean deleted = taskManager.deleteTask(taskId);
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
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (!EDITABLE_TASK_STATUSES.contains(task.getStatus())) {
                return badRequest("Task update failed: Only NEW or BLOCKED tasks can be updated");
            }

            validateKnownFields(requestBody, "task update");
            TaskCreateRequestDto request = toTaskUpdateRequest(requestBody);
            task.setTaskName(request.getTaskName());
            task.setProject(request.getProject());
            task.setTaskRoutingCode(request.getRoutingCode());
            task.setSharedConfig(request.getSharedConfig());
            if (task.getUser() != null) {
                task.getUser().setName(request.getUserId());
            }
            if (request.getBatchSize() > 0) {
                task.setBatchSize(request.getBatchSize());
            }
            taskManager.updateTask(task);
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
            int added = taskManager.appendTaskItems(taskId, inputs);
            return ok(Map.of(
                    "message", "Items appended",
                    "added", added
            ));
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
            boolean sealed = taskManager.sealTask(taskId);
            if (sealed) {
                Task task = taskManager.getTask(taskId);
                return ok(Map.of(
                        "message", "Task sealed",
                        "status", task != null ? task.getStatus().name() : ""
                ));
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
        List<TaskMsg> all = taskManager.getTaskMessages(taskId);
        int total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(from + size, total);
        List<TaskMsg> pageList = from < to ? all.subList(from, to) : Collections.emptyList();
        List<Map<String, Object>> messages = pageList.stream()
                .map(this::toTaskMessageView)
                .collect(Collectors.toList());
        return ok(Map.of(
                "total", total,
                "page", page,
                "size", size,
                "messages", messages
        ));
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
        item.put("routingCode", task.getTaskRoutingCode());
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

    private TaskCreateRequestDto toTaskCreateRequest(TaskCreateApiRequest requestBody) {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setUserId(requestBody.getUserId());
        request.setProject(requestBody.getProject());
        request.setTaskName(requestBody.getTaskName());
        request.setSharedConfig(requestBody.getSharedConfig());
        request.setInputs(requestBody.getInputs());
        request.setRoutingCode(requestBody.getRoutingCode());
        request.setBatchSize(requestBody.getBatchSize());
        request.setDefaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount());
        request.setOpenEnded(requestBody.isOpenEnded());
        request.setMaxRuntimeSeconds(requestBody.getMaxRuntimeSeconds());
        return request;
    }

    private TaskCreateRequestDto toTaskUpdateRequest(TaskUpdateApiRequest requestBody) {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setUserId(requestBody.getUserId());
        request.setProject(requestBody.getProject());
        request.setTaskName(requestBody.getTaskName());
        request.setSharedConfig(requestBody.getSharedConfig());
        request.setRoutingCode(requestBody.getRoutingCode());
        request.setBatchSize(requestBody.getBatchSize());
        return request;
    }

    private boolean isEmptyCreateRequest(TaskCreateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getTaskName() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getInputs() == null
                && requestBody.getRoutingCode() == null
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
                && requestBody.getRoutingCode() == null
                && requestBody.getBatchSize() == 0;
    }

    private boolean blockTask(Task task) {
        if (task == null) {
            return false;
        }
        if (task.getStatus() == TaskStatus.NEW) {
            return taskManager.rejectTask(task.getTid());
        }
        return taskManager.blockTask(task.getTid());
    }

    private boolean matchesKeyword(Task task, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsIgnoreCase(task.getTid(), normalizedKeyword)
                || containsIgnoreCase(task.getTaskName(), normalizedKeyword)
                || containsIgnoreCase(task.getProject(), normalizedKeyword)
                || containsIgnoreCase(task.getTaskRoutingCode(), normalizedKeyword);
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }
}
