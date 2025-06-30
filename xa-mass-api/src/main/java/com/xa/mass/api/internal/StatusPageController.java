package com.xa.mass.api.internal;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/status")
public class StatusPageController {
    @Autowired
    private TaskManager taskManager;
    @Autowired
    private DeviceManager deviceManager;
    @Autowired
    private RuleManager ruleManager;

    @GetMapping("")
    public String statusPage(Model model) {
        List<Task> allTasks = taskManager.getAllTasks();
        Map<TaskStatus, Long> taskStatusCount = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        List<Device> allDevices = deviceManager.getAllDevices();
        Map<DeviceStatus, Long> deviceStatusCount = allDevices.stream()
                .collect(Collectors.groupingBy(Device::getStatus, Collectors.counting()));
        List<Token> allTokens = deviceManager.getAllTokens();
        Map<String, Long> tokenStatusCount = allTokens.stream()
                .collect(Collectors.groupingBy(token -> token.getStatus().name(), Collectors.counting()));
        List<RuleDefinition> allRules = ruleManager.getDefaultRules();
        Map<RuleType, Long> ruleTypeCount = allRules.stream()
                .collect(Collectors.groupingBy(RuleDefinition::getType, Collectors.counting()));
        model.addAttribute("tasks", allTasks);
        model.addAttribute("taskStatusCount", taskStatusCount);
        model.addAttribute("devices", allDevices);
        model.addAttribute("deviceStatusCount", deviceStatusCount);
        model.addAttribute("tokens", allTokens);
        model.addAttribute("tokenStatusCount", tokenStatusCount);
        model.addAttribute("rules", allRules);
        model.addAttribute("ruleTypeCount", ruleTypeCount);
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("deviceStatuses", DeviceStatus.values());
        model.addAttribute("ruleTypes", RuleType.values());
        return "status";
    }

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

    @GetMapping("/devices")
    public String devicesPage(Model model) {
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Token> allTokens = deviceManager.getAllTokens();
        model.addAttribute("devices", allDevices);
        model.addAttribute("tokens", allTokens);
        model.addAttribute("deviceStatuses", DeviceStatus.values());
        Map<DeviceStatus, Long> deviceStatusCount = allDevices.stream()
            .collect(java.util.stream.Collectors.groupingBy(Device::getStatus, java.util.stream.Collectors.counting()));
        model.addAttribute("deviceStatusCount", deviceStatusCount);
        return "devices";
    }

    @GetMapping("/rules")
    public String rulesPage(Model model) {
        List<RuleDefinition> allRules = ruleManager.getDefaultRules();
        List<RuleType> registeredEvaluatorTypes = ruleManager.getRegisteredEvaluatorTypes();
        Map<RuleType, List<RuleDefinition>> rulesByType = new java.util.LinkedHashMap<>();
        for (RuleType type : RuleType.values()) {
            rulesByType.put(type, new java.util.ArrayList<>());
        }
        for (RuleDefinition rule : allRules) {
            rulesByType.get(rule.getType()).add(rule);
        }
        int total = allRules.size();
        Map<RuleType, String> ruleTypePercent = new java.util.LinkedHashMap<>();
        Map<RuleType, String> ruleTypePercentStyle = new java.util.LinkedHashMap<>();
        for (RuleType type : RuleType.values()) {
            int count = rulesByType.get(type).size();
            String percent = (total > 0) ? (count * 100 / total) + "%" : "0%";
            ruleTypePercent.put(type, percent);
            ruleTypePercentStyle.put(type, "width: " + percent);
        }
        model.addAttribute("rules", allRules);
        model.addAttribute("rulesByType", rulesByType);
        model.addAttribute("ruleTypes", RuleType.values());
        model.addAttribute("registeredEvaluatorTypes", registeredEvaluatorTypes);
        model.addAttribute("ruleTypePercent", ruleTypePercent);
        model.addAttribute("ruleTypePercentStyle", ruleTypePercentStyle);
        return "rules";
    }
} 