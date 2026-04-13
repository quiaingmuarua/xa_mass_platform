package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/status/api/tasks")
public class TaskApiController {
    @Autowired
    private TaskManager taskManager;

    @PostMapping("")
    public ResponseEntity<?> createTask(@RequestBody TaskCreateRequestDto request) {
        try {
            Task task = taskManager.createTask(request);
            return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务创建成功",
                    "taskId", task.getTid()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "任务创建失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                java.util.List<TaskMsg> msgs = taskManager.getTaskMessages(taskId);
                java.util.List<String> targetList = msgs.stream()
                        .map(TaskMsg::getTarget)
                        .collect(Collectors.toList());
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "task", task,
                        "targetList", targetList,
                        "stateValidation", taskManager.validateTaskState(taskId)
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "获取任务失败: " + e.getMessage()
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
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", "任务状态更新成功",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }

            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "当前任务状态不允许更新为 " + status.name()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "更新任务状态失败: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{taskId}/audit")
    public ResponseEntity<?> auditTask(@PathVariable String taskId, @RequestParam String approved, @RequestParam(required = false) String comment) {
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
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", isApproved ? "任务审核通过" : "任务审核拒绝",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }

            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "当前任务状态不允许审核"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "任务审核失败: " + e.getMessage()
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
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", "任务已暂停"
                ));
            }

            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "当前任务状态不允许暂停"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "暂停任务失败: " + e.getMessage()
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
                        ? "任务在暂停期间已完成，直接收口为终态"
                        : "任务已恢复";
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", message,
                        "newStatus", result.getStatus().name(),
                        "terminalReason", result.getTerminalReason() != null ? result.getTerminalReason().name() : ""
                ));
            }

            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "当前任务状态不允许恢复"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "恢复任务失败: " + e.getMessage()
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
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", "任务已中止"
                ));
            }

            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "当前任务状态不允许中止"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "中止任务失败: " + e.getMessage()
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
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", "任务删除成功"
                ));
            } else {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "success", false,
                        "message", "任务删除失败：当前状态 " + task.getStatus().name() + " 不允许删除"
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "删除任务失败: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<?> updateTask(@PathVariable String taskId, @RequestBody TaskCreateRequestDto request) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                task.setTaskName(request.getTaskName());
                task.setProject(request.getProject());
                task.setTaskCountry(request.getCountryCode());
                task.setTextContent(request.getTextContent());
                if (task.getUser() != null) {
                    task.getUser().setName(request.getUserId());
                }
                // Task does not have setTargetList; batch size can be set directly
                if (request.getBatchSize() > 0) {
                    task.setBatchSize(request.getBatchSize());
                }
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                        "success", true,
                        "message", "任务信息已更新"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "success", false,
                    "message", "更新任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 分页获取任务消息
     */
    @GetMapping("/{taskId}/messages")
    public java.util.Map<String, Object> getTaskMessages(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (size > 500) size = 500;
        java.util.List<TaskMsg> all = taskManager.getTaskMessages(taskId);
        int total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(from + size, total);
        java.util.List<TaskMsg> pageList = from < to ? all.subList(from, to) : java.util.Collections.emptyList();
        return java.util.Map.of(
                "success", true,
                "total", total,
                "page", page,
                "size", size,
                "messages", pageList
        );
    }
} 
