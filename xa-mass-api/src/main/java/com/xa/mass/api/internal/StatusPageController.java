package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/status")
public class StatusPageController {
    @Autowired
    private TaskManager taskManager;
    @Autowired
    private WorkerManager workerManager;
    @Autowired
    private RuleManager ruleManager;

    @GetMapping("")
    public String statusPage(Model model) {
        List<Task> allTasks = taskManager.getAllTasks();
        Map<TaskStatus, Long> taskStatusCount = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        List<Worker> allWorkers = workerManager.getAllWorkers();
        Map<WorkerStatus, Long> workerStatusCount = allWorkers.stream()
                .collect(Collectors.groupingBy(Worker::getStatus, Collectors.counting()));
        List<WorkerContext> allWorkerContexts = workerManager.getAllWorkerContexts();
        Map<String, Long> workerContextStatusCount = allWorkerContexts.stream()
                .collect(Collectors.groupingBy(wc -> wc.getStatus().name(), Collectors.counting()));
        List<RuleDefinition> allRules = ruleManager.getDefaultRules();
        Map<RuleType, Long> ruleTypeCount = allRules.stream()
                .collect(Collectors.groupingBy(RuleDefinition::getType, Collectors.counting()));
        model.addAttribute("tasks", allTasks);
        model.addAttribute("taskStatusCount", taskStatusCount);
        model.addAttribute("workers", allWorkers);
        model.addAttribute("workerStatusCount", workerStatusCount);
        model.addAttribute("workerContexts", allWorkerContexts);
        model.addAttribute("workerContextStatusCount", workerContextStatusCount);
        model.addAttribute("rules", allRules);
        model.addAttribute("ruleTypeCount", ruleTypeCount);
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("workerStatuses", WorkerStatus.values());
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

    @GetMapping("/workers")
    public String workersPage(Model model) {
        List<Worker> allWorkers = workerManager.getAllWorkers();
        List<WorkerContext> allWorkerContexts = workerManager.getAllWorkerContexts();
        HashSet<String> lockedWorkerIds = new HashSet<>(workerManager.getLockedWorkers());
        model.addAttribute("workers", allWorkers);
        model.addAttribute("workerContexts", allWorkerContexts);
        model.addAttribute("lockedWorkerIds", lockedWorkerIds);
        model.addAttribute("workerStatuses", WorkerStatus.values());
        Map<WorkerStatus, Long> workerStatusCount = allWorkers.stream()
                .collect(Collectors.groupingBy(Worker::getStatus, Collectors.counting()));
        model.addAttribute("workerStatusCount", workerStatusCount);
        return "workers";
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

    /**
     * 获取所有项目code，供前端多选下拉使用
     */
    @GetMapping("/workers/allProjects")
    @ResponseBody
    public List<String> getAllProjects() {
        return com.xa.mass.base.enums.Project.getAllCodes();
    }

    /**
     * 更新Worker支持的应用
     */
    @PostMapping("/workers/updateSupportedProjects")
    @ResponseBody
    public Map<String, Object> updateSupportedProjects(@RequestBody Map<String, Object> req) {
        String workerId = (String) req.get("workerId");
        Object supportedProjectsObj = req.get("supportedProjects");
        List<String> supportedProjects;
        if (supportedProjectsObj instanceof List) {
            supportedProjects = (List<String>) supportedProjectsObj;
        } else if (supportedProjectsObj instanceof String) {
            supportedProjects = Arrays.asList(((String) supportedProjectsObj).split(","));
        } else {
            supportedProjects = List.of();
        }
        Worker worker = workerManager.getWorker(workerId);
        if (worker != null) {
            worker.setSupportedProjects(supportedProjects);
            workerManager.updateWorker(worker);
            return Map.of("success", true, "msg", "更新成功");
        } else {
            return Map.of("success", false, "msg", "Worker不存在");
        }
    }
}
