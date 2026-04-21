package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final Set<String> SUPPORTED_TASK_CREATE_FIELDS = Set.of(
            "userId",
            "project",
            "taskName",
            "sharedConfig",
            "inputs",
            "routingCode",
            "batchSize",
            "defaultMsgMaxRetryCount",
            "openEnded",
            "maxRuntimeSeconds"
    );
    private static final Set<String> SUPPORTED_TASK_UPDATE_FIELDS = Set.of(
            "userId",
            "project",
            "taskName",
            "sharedConfig",
            "routingCode",
            "batchSize"
    );
    private static final Set<TaskStatus> EDITABLE_TASK_STATUSES = Set.of(TaskStatus.NEW, TaskStatus.BLOCKED);

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> listTasks(@RequestParam(required = false) String keyword,
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
            return ResponseEntity.ok(success(Map.of(
                    "items", items,
                    "total", items.size()
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task list failed: " + e.getMessage()));
        }
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> requestBody) {
        try {
            TaskCreateRequestDto request = parseTaskRequest(requestBody, SUPPORTED_TASK_CREATE_FIELDS, "task create");
            Task task = taskManager.createTask(request);
            return ResponseEntity.ok(success(Map.of(
                    "taskId", task.getTid(),
                    "message", "Task created"
            )));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task create failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).build();
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
            return ResponseEntity.ok(success(response));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task lookup failed: " + e.getMessage()));
        }
    }

    @PutMapping("/{taskId}/status")
    public ResponseEntity<Map<String, Object>> updateTaskStatus(@PathVariable String taskId,
                                                                @RequestParam TaskStatus status) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
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
                return ResponseEntity.ok(success(Map.of(
                        "message", "Task status updated",
                        "newStatus", updatedTask.getStatus().name()
                )));
            }

            return ResponseEntity.status(409).body(error("Task cannot transition to status " + status.name()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task status update failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/block")
    public ResponseEntity<Map<String, Object>> blockTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            if (blockTask(task)) {
                return ResponseEntity.ok(success(Map.of("message", "Task blocked")));
            }

            return ResponseEntity.status(409).body(error("Task cannot be blocked from the current state"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task block failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/audit")
    public ResponseEntity<Map<String, Object>> auditTask(@PathVariable String taskId,
                                                         @RequestParam String approved,
                                                         @RequestParam(required = false) String comment) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            boolean isApproved = "true".equalsIgnoreCase(approved);
            boolean success = isApproved
                    ? taskManager.approveTask(taskId)
                    : taskManager.rejectTask(taskId);
            Task updatedTask = taskManager.getTask(taskId);

            if (success && updatedTask != null) {
                return ResponseEntity.ok(success(Map.of(
                        "message", isApproved ? "Task approved" : "Task rejected",
                        "newStatus", updatedTask.getStatus().name()
                )));
            }

            return ResponseEntity.badRequest().body(error("Task cannot be audited from the current state"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task audit failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<Map<String, Object>> pauseTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            if (taskManager.pauseTask(taskId)) {
                return ResponseEntity.ok(success(Map.of("message", "Task paused")));
            }

            return ResponseEntity.status(409).body(error("Task cannot be paused from the current state"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task pause failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<Map<String, Object>> resumeTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            TaskResumeResult result = taskManager.resumeTaskDetailed(taskId);
            if (result.isSuccess()) {
                String message = result.getOutcome() == TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL
                        ? "Task already completed while paused and was closed to TERMINAL"
                        : "Task resumed";
                return ResponseEntity.ok(success(Map.of(
                        "message", message,
                        "newStatus", result.getStatus().name(),
                        "terminalReason", result.getTerminalReason() != null ? result.getTerminalReason().name() : ""
                )));
            }

            return ResponseEntity.status(409).body(error("Task cannot be resumed from the current state"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task resume failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/terminate")
    public ResponseEntity<Map<String, Object>> terminateTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            if (taskManager.cancelTask(taskId)) {
                return ResponseEntity.ok(success(Map.of("message", "Task terminated")));
            }

            return ResponseEntity.status(409).body(error("Task cannot be terminated from the current state"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task terminate failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }

            boolean deleted = taskManager.deleteTask(taskId);
            if (deleted) {
                return ResponseEntity.ok(success(Map.of("message", "Task deleted")));
            }

            return ResponseEntity.badRequest().body(error(
                    "Task delete failed: current status " + task.getStatus().name() + " cannot be deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task delete failed: " + e.getMessage()));
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(@PathVariable String taskId,
                                                          @RequestBody Map<String, Object> requestBody) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(error("Task not found: " + taskId));
            }
            if (!EDITABLE_TASK_STATUSES.contains(task.getStatus())) {
                return ResponseEntity.badRequest().body(error("Task update failed: Only NEW or BLOCKED tasks can be updated"));
            }

            TaskCreateRequestDto request = parseTaskRequest(requestBody, SUPPORTED_TASK_UPDATE_FIELDS, "task update");
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
            return ResponseEntity.ok(success(Map.of("message", "Task updated")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Task update failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/items")
    public ResponseEntity<Map<String, Object>> appendTaskItems(@PathVariable String taskId,
                                                               @RequestBody Map<String, Object> requestBody) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> inputs =
                    (List<Map<String, Object>>) requestBody.get("inputs");
            if (inputs == null || inputs.isEmpty()) {
                return ResponseEntity.badRequest().body(error("inputs must be a non-empty list"));
            }
            int added = taskManager.appendTaskItems(taskId, inputs);
            return ResponseEntity.ok(success(Map.of(
                    "message", "Items appended",
                    "added", added
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error("Task not found: " + taskId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Append items failed: " + e.getMessage()));
        }
    }

    @PutMapping("/{taskId}/seal")
    public ResponseEntity<Map<String, Object>> sealTask(@PathVariable String taskId) {
        try {
            boolean sealed = taskManager.sealTask(taskId);
            if (sealed) {
                Task task = taskManager.getTask(taskId);
                return ResponseEntity.ok(success(Map.of(
                        "message", "Task sealed",
                        "status", task != null ? task.getStatus().name() : ""
                )));
            }
            return ResponseEntity.status(409).body(error("Task not found or not open-ended"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("Seal task failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{taskId}/messages")
    public Map<String, Object> getTaskMessages(@PathVariable String taskId,
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
        return success(Map.of(
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

    private Map<String, Object> success(Map<String, ?> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", Boolean.TRUE);
        result.putAll(data);
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", Boolean.FALSE);
        result.put("message", message);
        return result;
    }

    private TaskCreateRequestDto parseTaskRequest(Map<String, Object> requestBody,
                                                  Set<String> supportedFields,
                                                  String operationName) {
        if (requestBody == null || requestBody.isEmpty()) {
            throw new IllegalArgumentException("task request body is required");
        }

        List<String> unknownFields = requestBody.keySet().stream()
                .filter(field -> !supportedFields.contains(field))
                .sorted()
                .collect(Collectors.toList());
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: " + String.join(", ", unknownFields));
        }

        return objectMapper.convertValue(requestBody, TaskCreateRequestDto.class);
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
