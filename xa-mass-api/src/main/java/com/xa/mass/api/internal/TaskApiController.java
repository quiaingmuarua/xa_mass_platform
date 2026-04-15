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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/status/api/tasks")
public class TaskApiController {
    private static final Set<String> SUPPORTED_TASK_CREATE_FIELDS = Set.of(
            "userId",
            "project",
            "taskName",
            "sharedConfig",
            "targetList",
            "countryCode",
            "batchSize",
            "defaultMsgMaxRetryCount",
            "openEnded"
    );
    private static final Set<String> SUPPORTED_TASK_UPDATE_FIELDS = Set.of(
            "userId",
            "project",
            "taskName",
            "sharedConfig",
            "countryCode",
            "batchSize"
    );
    private static final Set<TaskStatus> EDITABLE_TASK_STATUSES = Set.of(TaskStatus.NEW, TaskStatus.BLOCKED);

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("")
    public ResponseEntity<?> createTask(@RequestBody Map<String, Object> requestBody) {
        try {
            TaskCreateRequestDto request = parseTaskRequest(requestBody, SUPPORTED_TASK_CREATE_FIELDS, "task create");
            Task task = taskManager.createTask(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Task created",
                    "taskId", task.getTid()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task create failed: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            List<TaskMsg> msgs = taskManager.getTaskMessages(taskId);
            List<String> targetList = msgs.stream()
                    .map(TaskMsg::getTarget)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "task", task,
                    "targetList", targetList,
                    "stateValidation", taskManager.validateTaskState(taskId)
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task lookup failed: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/{taskId}/status")
    public ResponseEntity<?> updateTaskStatus(@PathVariable String taskId, @RequestParam TaskStatus status) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            boolean success = switch (status) {
                case READY -> task.getStatus() == TaskStatus.PAUSED
                        ? taskManager.resumeTaskDetailed(taskId).isSuccess()
                        : taskManager.approveTask(taskId);
                case BLOCKED -> taskManager.rejectTask(taskId);
                case PAUSED -> taskManager.pauseTask(taskId);
                case TERMINAL -> taskManager.cancelTask(taskId);
                default -> false;
            };

            Task updatedTask = taskManager.getTask(taskId);
            if (success && updatedTask != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Task status updated",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task cannot transition to status " + status.name()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task status update failed: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{taskId}/audit")
    public ResponseEntity<?> auditTask(@PathVariable String taskId,
                                       @RequestParam String approved,
                                       @RequestParam(required = false) String comment) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            boolean isApproved = "true".equalsIgnoreCase(approved);
            boolean success = isApproved
                    ? taskManager.approveTask(taskId)
                    : taskManager.rejectTask(taskId);
            Task updatedTask = taskManager.getTask(taskId);

            if (success && updatedTask != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", isApproved ? "Task approved" : "Task rejected",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task cannot be audited from the current state"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task audit failed: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<?> pauseTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            if (taskManager.pauseTask(taskId)) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Task paused"
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task cannot be paused from the current state"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task pause failed: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<?> resumeTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            TaskResumeResult result = taskManager.resumeTaskDetailed(taskId);
            if (result.isSuccess()) {
                String message = result.getOutcome() == TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL
                        ? "Task already completed while paused and was closed to TERMINAL"
                        : "Task resumed";
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", message,
                        "newStatus", result.getStatus().name(),
                        "terminalReason", result.getTerminalReason() != null ? result.getTerminalReason().name() : ""
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task cannot be resumed from the current state"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task resume failed: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{taskId}/terminate")
    public ResponseEntity<?> terminateTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            if (taskManager.cancelTask(taskId)) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Task terminated"
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task cannot be terminated from the current state"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task terminate failed: " + e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> deleteTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            boolean deleted = taskManager.deleteTask(taskId);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Task deleted"
                ));
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task delete failed: current status " + task.getStatus().name() + " cannot be deleted"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task delete failed: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<?> updateTask(@PathVariable String taskId, @RequestBody Map<String, Object> requestBody) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }
            if (!EDITABLE_TASK_STATUSES.contains(task.getStatus())) {
                throw new IllegalStateException("Only NEW or BLOCKED tasks can be updated");
            }

            TaskCreateRequestDto request = parseTaskRequest(requestBody, SUPPORTED_TASK_UPDATE_FIELDS, "task update");
            task.setTaskName(request.getTaskName());
            task.setProject(request.getProject());
            task.setTaskRoutingCountryCode(request.getCountryCode());
            task.setSharedConfig(request.getSharedConfig());
            if (task.getUser() != null) {
                task.getUser().setName(request.getUserId());
            }
            if (request.getBatchSize() > 0) {
                task.setBatchSize(request.getBatchSize());
            }
            taskManager.updateTask(task);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Task updated"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task update failed: " + e.getMessage()
            ));
        }
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

    @PostMapping("/{taskId}/items")
    public ResponseEntity<?> appendTaskItems(@PathVariable String taskId,
                                             @RequestBody Map<String, Object> requestBody) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> inputs =
                    (java.util.List<java.util.Map<String, Object>>) requestBody.get("inputs");
            if (inputs == null || inputs.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "inputs must be a non-empty list"
                ));
            }
            int added = taskManager.appendTaskItems(taskId, inputs);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Items appended",
                    "added", added
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Append items failed: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/{taskId}/seal")
    public ResponseEntity<?> sealTask(@PathVariable String taskId) {
        try {
            boolean sealed = taskManager.sealTask(taskId);
            if (sealed) {
                Task task = taskManager.getTask(taskId);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Task sealed",
                        "status", task != null ? task.getStatus().name() : ""
                ));
            }
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Task not found or not open-ended"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Seal task failed: " + e.getMessage()
            ));
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
        List<TaskMsg> pageList = from < to ? all.subList(from, to) : java.util.Collections.emptyList();
        return Map.of(
                "success", true,
                "total", total,
                "page", page,
                "size", size,
                "messages", pageList
        );
    }
}
