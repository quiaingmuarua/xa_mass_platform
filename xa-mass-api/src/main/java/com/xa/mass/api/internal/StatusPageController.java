package com.xa.mass.api.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.CodecContext;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.session.SessionRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/status")
public class StatusPageController {
    private static final Gson GSON = new Gson();

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
        Map<String, Long> messageStats = new LinkedHashMap<>();
        Map<TaskMsgStatus, Long> messageStatusCount = allTasks.stream()
                .flatMap(task -> taskManager.getTaskMessages(task.getTid()).stream())
                .filter(taskMsg -> taskMsg.getStatus() != null)
                .collect(Collectors.groupingBy(taskMsg -> taskMsg.getStatus(), Collectors.counting()));
        for (TaskMsgStatus status : TaskMsgStatus.values()) {
            messageStats.put(status.name(), messageStatusCount.getOrDefault(status, 0L));
        }
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
        model.addAttribute("messageStats", messageStats);
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
     * Return all supported project codes for worker-edit forms.
     */
    @GetMapping("/workers/allProjects")
    @ResponseBody
    public List<String> getAllProjects() {
        return com.xa.mass.base.enums.Project.getAllCodes();
    }

    /**
     * Replace the supported project list for a worker.
     */
    @PostMapping("/workers/updateSupportedProjects")
    @ResponseBody
    @SuppressWarnings("unchecked")
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
            return Map.of("success", true, "msg", "Updated successfully");
        }
        return Map.of("success", false, "msg", "Worker not found");
    }

    @PostMapping("/workers/send-message")
    @ResponseBody
    public Map<String, Object> sendWorkerMessage(@RequestBody Map<String, Object> req) {
        String workerId = readTrimmed(req.get("workerId"));
        if (workerId == null) {
            return Map.of("success", false, "msg", "workerId is required");
        }

        Worker worker = workerManager.getWorker(workerId);
        if (worker == null) {
            return Map.of("success", false, "msg", "Worker not found");
        }

        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext == null || transportContext.getMessageTransporter() == null) {
            return Map.of("success", false, "msg", "Message transporter is not initialized");
        }

        SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
        if (sessionContext == null || sessionContext.getSessionManager() == null) {
            return Map.of("success", false, "msg", "Session manager is not initialized");
        }

        ServerSessionManager sessionManager = sessionContext.getSessionManager();
        if (!sessionManager.isWorkerOnline(workerId, SessionRoles.TASK_MESSAGES)) {
            return Map.of("success", false, "msg", "Target worker is offline or task_messages session is unavailable");
        }

        String project;
        try {
            project = resolveProjectCode(req.get("project"), worker);
        } catch (IllegalArgumentException ex) {
            return Map.of("success", false, "msg", ex.getMessage());
        }

        MessageType messageType;
        try {
            messageType = parseMessageType(req.get("msgType"));
        } catch (IllegalArgumentException ex) {
            return Map.of("success", false, "msg", ex.getMessage());
        }

        JsonElement payload;
        try {
            payload = toPayloadJson(req.get("payload"));
        } catch (IllegalArgumentException ex) {
            return Map.of("success", false, "msg", ex.getMessage());
        }

        String msgId = UUID.randomUUID().toString();
        String subMsgType = defaultIfBlank(readTrimmed(req.get("subMsgType")), "manual");

        MassMessage message = new MassMessage();
        message.setMsgId(msgId);
        message.setMsgType(messageType);
        message.setSubMsgType(subMsgType);
        message.setFrom(MessageDirection.SERVER);
        message.setProject(project);
        message.setContext(buildMessageContext(workerId));
        message.setPayload(payload);

        String rawJson = encodeMessage(message);
        Envelope envelope = Envelope.builder()
                .workerId(workerId)
                .connRole(SessionRoles.TASK_MESSAGES)
                .project(project)
                .traceId(msgId)
                .receivedAt(System.currentTimeMillis())
                .rawJson(rawJson)
                .build();
        transportContext.getMessageTransporter().sendOutput(envelope);

        return Map.of(
                "success", true,
                "msg", "Message queued",
                "messageId", msgId,
                "workerId", workerId,
                "project", project,
                "msgType", messageType.name(),
                "subMsgType", subMsgType
        );
    }

    private MessageContext buildMessageContext(String workerId) {
        MessageContext context = new MessageContext();
        context.setWorkerId(workerId);
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        return context;
    }

    private String encodeMessage(MassMessage message) {
        CodecContext codecContext = DispatcherContextRegistry.getCodecContext();
        MessageCodec codec = codecContext != null ? codecContext.getMessageCodec() : null;
        return codec != null ? codec.encode(message) : GSON.toJson(message);
    }

    private JsonElement toPayloadJson(Object payloadObj) {
        if (payloadObj == null) {
            return GSON.toJsonTree(Map.of());
        }
        if (payloadObj instanceof String payloadText) {
            String trimmed = payloadText.trim();
            if (trimmed.isEmpty()) {
                return GSON.toJsonTree(Map.of());
            }
            try {
                return GSON.fromJson(trimmed, JsonElement.class);
            } catch (JsonSyntaxException ex) {
                throw new IllegalArgumentException("payload must be valid JSON");
            }
        }
        return GSON.toJsonTree(payloadObj);
    }

    private MessageType parseMessageType(Object messageTypeObj) {
        String text = defaultIfBlank(readTrimmed(messageTypeObj), MessageType.TASK.name());
        try {
            return MessageType.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported msgType: " + text);
        }
    }

    private String resolveProjectCode(Object projectObj, Worker worker) {
        String requestedProject = readTrimmed(projectObj);
        if (requestedProject != null) {
            return Project.requireCode(requestedProject).getCode();
        }
        List<String> supportedProjects = worker.getSupportedProjects();
        if (supportedProjects != null && !supportedProjects.isEmpty()) {
            return Project.requireCode(supportedProjects.get(0)).getCode();
        }
        return Project.DEMO_APP.getCode();
    }

    private String readTrimmed(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
