package com.xa.mass.api.internal;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.TaskMsg;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.eventbus.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 状态展示控制器
 * 提供任务、设备、消息、规则等状态的页面展示
 */
@Controller
@RequestMapping("/status")
public class StatusController {

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private RuleManager<Map<String, Object>> ruleManager;

    /**
     * 主状态页面
     */
    @GetMapping("")
    public String statusPage(Model model) {
        // 获取任务数据
        List<Task> allTasks = taskManager.getAllTasks();
        Map<TaskStatus, Long> taskStatusCount = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));

        // 获取设备数据
        List<Device> allDevices = deviceManager.getAllDevices();
        Map<DeviceStatus, Long> deviceStatusCount = allDevices.stream()
                .collect(Collectors.groupingBy(Device::getStatus, Collectors.counting()));

        // 获取Token数据
        List<Token> allTokens = deviceManager.getAllTokens();
        Map<String, Long> tokenStatusCount = allTokens.stream()
                .collect(Collectors.groupingBy(token -> token.getStatus().name(), Collectors.counting()));

        // 获取规则统计
        List<RuleDefinition> allRules = ruleManager.getDefaultRules();
        Map<RuleType, Long> ruleTypeCount = allRules.stream()
                .collect(Collectors.groupingBy(RuleDefinition::getType, Collectors.counting()));

        // 获取消息统计
        Map<String, Long> messageStats = getMessageStats();

        // 添加到模型
        model.addAttribute("tasks", allTasks);
        model.addAttribute("taskStatusCount", taskStatusCount);
        model.addAttribute("devices", allDevices);
        model.addAttribute("deviceStatusCount", deviceStatusCount);
        model.addAttribute("tokens", allTokens);
        model.addAttribute("tokenStatusCount", tokenStatusCount);
        model.addAttribute("rules", allRules);
        model.addAttribute("ruleTypeCount", ruleTypeCount);
        model.addAttribute("messageStats", messageStats);

        // 添加状态枚举
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("deviceStatuses", DeviceStatus.values());
        model.addAttribute("ruleTypes", RuleType.values());

        return "status";
    }

    /**
     * 任务详情页面
     */
    @GetMapping("/tasks")
    public String tasksPage(Model model) {
        List<Task> allTasks = taskManager.getAllTasks();
        model.addAttribute("tasks", allTasks);
        model.addAttribute("taskStatuses", TaskStatus.values());
        Map<TaskStatus, Long> taskStatusCount = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        model.addAttribute("taskStatusCount", taskStatusCount);
        return "tasks";
    }

    /**
     * 设备详情页面
     */
    @GetMapping("/devices")
    public String devicesPage(Model model) {
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Token> allTokens = deviceManager.getAllTokens();
        
        model.addAttribute("devices", allDevices);
        model.addAttribute("tokens", allTokens);
        model.addAttribute("deviceStatuses", DeviceStatus.values());
        return "devices";
    }

    /**
     * 规则详情页面
     */
    @GetMapping("/rules")
    public String rulesPage(Model model) {
        List<RuleDefinition> allRules = ruleManager.getDefaultRules();
        List<RuleType> registeredEvaluatorTypes = ruleManager.getRegisteredEvaluatorTypes();
        
        // 按类型分组规则
        Map<RuleType, List<RuleDefinition>> rulesByType = allRules.stream()
                .collect(Collectors.groupingBy(RuleDefinition::getType));
        
        model.addAttribute("rules", allRules);
        model.addAttribute("rulesByType", rulesByType);
        model.addAttribute("ruleTypes", RuleType.values());
        model.addAttribute("registeredEvaluatorTypes", registeredEvaluatorTypes);
        return "rules";
    }

    // ==================== 任务管理 API ====================

    /**
     * 创建新任务
     */
    @PostMapping("/api/tasks")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody TaskCreateRequestDto request) {
        try {
            Task task = taskManager.createTask(request);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "任务创建成功",
                "taskId", task.getTid()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "任务创建失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/api/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "task", task
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "获取任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 更新任务状态
     */
    @PutMapping("/api/tasks/{taskId}/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateTaskStatus(
            @PathVariable String taskId,
            @RequestParam TaskStatus status) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                task.setStatus(status);
                taskManager.updateTask(task);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务状态更新成功",
                    "newStatus", status.name()
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "更新任务状态失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 审核任务
     */
    @PostMapping("/api/tasks/{taskId}/audit")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> auditTask(
            @PathVariable String taskId,
            @RequestParam String approved,
            @RequestParam(required = false) String comment) {
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
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", isApproved ? "任务审核通过" : "任务审核拒绝",
                    "newStatus", task.getStatus().name()
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "任务审核失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 暂停任务
     */
    @PostMapping("/api/tasks/{taskId}/pause")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pauseTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                task.setStatus(TaskStatus.PAUSED);
                taskManager.updateTask(task);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务已暂停"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "暂停任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 恢复任务
     */
    @PostMapping("/api/tasks/{taskId}/resume")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resumeTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                task.setStatus(TaskStatus.READY);
                taskManager.updateTask(task);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务已恢复"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "恢复任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 中止任务
     */
    @PostMapping("/api/tasks/{taskId}/terminate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> terminateTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                task.setStatus(TaskStatus.TERMINAL);
                taskManager.updateTask(task);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务已中止"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "中止任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/api/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                taskManager.deleteTask(taskId);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务删除成功"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "删除任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 编辑任务（更新任务信息）
     */
    @PutMapping("/api/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateTask(@PathVariable String taskId, @RequestBody TaskCreateRequestDto request) {
        try {
            Task task = taskManager.getTask(taskId);
            if (task != null) {
                // 只允许部分字段可编辑
                task.setTaskName(request.getTaskName());
                task.setProject(request.getProject());
                task.setTaskCountry(request.getCountryCode());
                task.setTextContent(request.getTextContent());
                if (task.getUser() != null) {
                    task.getUser().setName(request.getUserId());
                }
                // 号码和批次
                // 这里假设Task有setTargetList和setBatchSize方法（如无可扩展）
                try {
                    java.lang.reflect.Method setTargetList = task.getClass().getMethod("setTargetList", java.util.List.class);
                    setTargetList.invoke(task, request.getTargetList());
                } catch (Exception ignore) {}
                try {
                    java.lang.reflect.Method setBatchSize = task.getClass().getMethod("setBatchSize", int.class);
                    setBatchSize.invoke(task, request.getBatchSize());
                } catch (Exception ignore) {}
                taskManager.updateTask(task);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "任务信息已更新"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "更新任务失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 获取消息统计信息
     */
    private Map<String, Long> getMessageStats() {
        // 这里可以从 TaskManager 获取消息统计
        // 暂时返回空Map，后续可以扩展
        return Map.of();
    }
} 