package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
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
                    "targetList", targetList
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
            if (task != null) {
                task.setStatus(status);
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务状态更新成功",
                    "newStatus", status.name()
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
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
            if (task != null) {
                boolean isApproved = "true".equalsIgnoreCase(approved);
                if (isApproved) {
                    task.setStatus(TaskStatus.READY);
                } else {
                    task.setStatus(TaskStatus.BLOCKED);
                }
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", isApproved ? "任务审核通过" : "任务审核拒绝",
                    "newStatus", task.getStatus().name()
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
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
            if (task != null) {
                task.setStatus(TaskStatus.PAUSED);
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务已暂停"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
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
            if (task != null) {
                task.setStatus(TaskStatus.READY);
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务已恢复"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
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
            if (task != null) {
                task.setStatus(TaskStatus.TERMINAL);
                taskManager.updateTask(task);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务已中止"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
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
            if (task != null) {
                taskManager.deleteTask(taskId);
                return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "message", "任务删除成功"
                ));
            } else {
                return ResponseEntity.notFound().build();
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
                try {
                    java.lang.reflect.Method setTargetList = task.getClass().getMethod("setTargetList", java.util.List.class);
                    setTargetList.invoke(task, request.getTargetList());
                } catch (Exception ignore) {}
                try {
                    java.lang.reflect.Method setBatchSize = task.getClass().getMethod("setBatchSize", int.class);
                    setBatchSize.invoke(task, request.getBatchSize());
                } catch (Exception ignore) {}
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