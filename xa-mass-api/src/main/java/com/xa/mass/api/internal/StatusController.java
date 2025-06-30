package com.xa.mass.api.internal;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.TaskMsg;
import com.xa.mass.eventbus.model.Token;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 状态展示控制器
 * 提供任务、设备、消息等状态的页面展示
 */
@Controller
@RequestMapping("/status")
public class StatusController {

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private DeviceManager deviceManager;

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

        // 获取消息统计
        Map<String, Long> messageStats = getMessageStats();

        // 添加到模型
        model.addAttribute("tasks", allTasks);
        model.addAttribute("taskStatusCount", taskStatusCount);
        model.addAttribute("devices", allDevices);
        model.addAttribute("deviceStatusCount", deviceStatusCount);
        model.addAttribute("tokens", allTokens);
        model.addAttribute("tokenStatusCount", tokenStatusCount);
        model.addAttribute("messageStats", messageStats);

        // 添加状态枚举
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("deviceStatuses", DeviceStatus.values());

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
     * 获取消息统计信息
     */
    private Map<String, Long> getMessageStats() {
        // 这里可以从 TaskManager 获取消息统计
        // 暂时返回空Map，后续可以扩展
        return Map.of();
    }
} 