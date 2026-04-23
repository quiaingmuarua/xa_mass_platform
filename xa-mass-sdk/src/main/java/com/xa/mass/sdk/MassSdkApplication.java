package com.xa.mass.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
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
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.auth.InMemorySubmitterRegistry;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.MassTaskRequestMapper;
import com.xa.mass.sdk.model.SdkResourceMapper;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.sdk.worker.PollingWorkerSession;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointRoles;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Consumer-facing runtime handle returned by the SDK facade.
 *
 * <p>The SDK artifact also carries the lower-level {@link MassApplication}
 * runtime. This wrapper keeps the common lifecycle surface explicit while
 * still allowing an escape hatch through {@link #unwrap()} for advanced
 * embedding paths. {@code com.xa.mass.sdk.*} is the stable public surface;
 * lower-level starter/runtime types remain advanced integration seams.
 */
public final class MassSdkApplication implements MassRuntimeControl, TaskOperations, WorkerOperations,
        ResourceOperations, CatalogOperations,
        RuleOperations, TransportOperations, DebugOperations {

    private static final Gson GSON = new Gson();
    private static final String MANUAL_DEBUG_SUB_MSG_TYPE = "manual-chat";

    private final MassApplication delegate;
    private final ProjectEventCatalogRegistry projectEventCatalogRegistry;
    private final InMemorySubmitterRegistry submitterRegistry;

    MassSdkApplication(MassApplication delegate) {
        this(delegate, DefaultProjectEventCatalogFactory.createDefaultRegistry());
    }

    MassSdkApplication(MassApplication delegate, ProjectEventCatalogRegistry projectEventCatalogRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.projectEventCatalogRegistry = Objects.requireNonNull(projectEventCatalogRegistry, "projectEventCatalogRegistry");
        this.submitterRegistry = new InMemorySubmitterRegistry();
        registerEnabledCatalogProjectsIntoCore();
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }

    public boolean isRunning() {
        return delegate.isRunning();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This runtime handle
     * remains as an advanced compatibility seam for embedding paths that still
     * need direct engine access.
     */
    @Deprecated(forRemoval = false)
    public MassEngine getEngine() {
        return delegate.getEngine();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This manager
     * access remains as an advanced compatibility seam.
     */
    @Deprecated(forRemoval = false)
    public TaskManager getTaskManager() {
        return getEngine() != null ? getEngine().getTaskManager() : null;
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This manager
     * access remains as an advanced compatibility seam.
     */
    @Deprecated(forRemoval = false)
    public WorkerManager getWorkerManager() {
        return getEngine() != null ? getEngine().getWorkerManager() : null;
    }

    @Override
    public Task createTask(MassTaskCreateRequest request) {
        MassEngine engine = requireStartedEngine();
        return engine.createTask(SdkResourceMapper.toEngineRequest(request));
    }

    @Override
    public Task createTask(MassTaskRequest request) {
        MassEngine engine = requireStartedEngine();
        validateTaskCatalogContract(request);
        return engine.createTask(MassTaskRequestMapper.toEngineRequest(request));
    }

    /**
     * @deprecated Prefer {@link #createTask(MassTaskCreateRequest)} so SDK callers
     * stay independent from engine DTO packages.
     */
    @Deprecated(forRemoval = false)
    public Task createTask(TaskCreateRequestDto request) {
        return requireStartedEngine().createTask(request);
    }

    public Task getTask(String taskId) {
        return requireStartedTaskManager().getTask(taskId);
    }

    public List<Task> getAllTasks() {
        return requireStartedTaskManager().getAllTasks();
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return requireStartedTaskManager().getTasksByStatus(status);
    }

    public boolean approveTask(String taskId) {
        return requireStartedTaskManager().approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return requireStartedTaskManager().rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return requireStartedTaskManager().blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return requireStartedTaskManager().pauseTask(taskId);
    }

    public SdkTaskResumeResult resumeTaskDetailed(String taskId) {
        TaskResumeResult result = requireStartedTaskManager().resumeTaskDetailed(taskId);
        return new SdkTaskResumeResult(
                result.isSuccess(),
                result.getStatus() != null ? result.getStatus().name() : null,
                result.getTerminalReason() != null ? result.getTerminalReason().name() : null,
                result.getOutcome() == TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL
        );
    }

    public boolean resumeTask(String taskId) {
        return requireStartedTaskManager().resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return requireStartedTaskManager().cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return requireStartedTaskManager().terminateTask(taskId, reason);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return requireStartedTaskManager().appendTaskItems(taskId, inputs);
    }

    public boolean sealTask(String taskId) {
        return requireStartedTaskManager().sealTask(taskId);
    }

    public List<TaskMsg> getTaskMessages(String taskId) {
        return requireStartedTaskManager().getTaskMessages(taskId);
    }

    public TaskStateResolutionResult resolveTaskStateFromMessages(String taskId) {
        return requireStartedTaskManager().resolveTaskStateFromMessages(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return requireStartedTaskManager().validateTaskState(taskId);
    }

    @Override
    public boolean updateTask(Task task) {
        return requireStartedTaskManager().updateTask(task);
    }

    @Override
    public boolean deleteTask(String taskId) {
        return requireStartedTaskManager().deleteTask(taskId);
    }

    @Override
    public void registerWorker(WorkerRegistration request) {
        requireStartedEngine().addWorker(SdkResourceMapper.toWorker(request));
    }

    @Override
    public void registerWorkerContext(WorkerContextRegistration request) {
        requireStartedEngine().addWorkerContext(SdkResourceMapper.toWorkerContext(request));
    }

    /**
     * @deprecated Prefer {@link #registerWorker(WorkerRegistration)} so SDK callers
     * do not need to construct core runtime models directly.
     */
    @Deprecated(forRemoval = false)
    @Override
    public void addWorker(Worker worker) {
        requireStartedEngine().addWorker(worker);
    }

    /**
     * @deprecated Prefer {@link #registerWorkerContext(WorkerContextRegistration)} so
     * SDK callers do not need to construct core runtime models directly.
     */
    @Deprecated(forRemoval = false)
    @Override
    public void addWorkerContext(WorkerContext workerContext) {
        requireStartedEngine().addWorkerContext(workerContext);
    }

    public Worker getWorker(String workerId) {
        return requireStartedWorkerManager().getWorker(workerId);
    }

    public List<Worker> getAllWorkers() {
        return requireStartedWorkerManager().getAllWorkers();
    }

    public List<WorkerContext> getAllWorkerContexts() {
        return requireStartedWorkerManager().getAllWorkerContexts();
    }

    public List<WorkerContext> getWorkerContexts(String workerId) {
        return requireStartedWorkerManager().getWorkerContexts(workerId);
    }

    public PullWorkerSession pullWorker(String workerId) {
        requireStartedEngine();
        return delegate.openPullWorkerSession(workerId);
    }

    /**
     * @deprecated Prefer {@link #pullWorker(String)}. This wrapper remains as a
     * compatibility surface for existing polling-style worker callers.
     */
    @Deprecated(forRemoval = false)
    public PollingWorkerSession pollingWorker(String workerId) {
        requireStartedEngine();
        return delegate.openPollingWorkerSession(workerId);
    }

    public WorkerContext getWorkerContextById(String workerContextId) {
        return requireStartedWorkerManager().getWorkerContextById(workerContextId);
    }

    public boolean isWorkerLocked(String workerId) {
        return requireStartedWorkerManager().isLocked(workerId);
    }

    public boolean isWorkerOnline(String workerId) {
        return requireStartedWorkerManager().isWorkerOnline(workerId);
    }

    @Override
    public boolean updateWorker(Worker worker) {
        return requireStartedWorkerManager().updateWorker(worker);
    }

    @Override
    public void registerProject(ProjectMetadata projectMetadata) {
        projectEventCatalogRegistry.registerProject(projectMetadata);
        registerProjectIntoCore(projectMetadata);
    }

    @Override
    public void registerEvent(EventMetadata eventMetadata) {
        projectEventCatalogRegistry.registerEvent(eventMetadata);
    }

    @Override
    public List<ProjectMetadata> listProjects() {
        return projectEventCatalogRegistry.listProjects();
    }

    @Override
    public ProjectMetadata getProject(String projectCode) {
        return projectEventCatalogRegistry.getProject(projectCode);
    }

    @Override
    public List<EventMetadata> listEvents() {
        return projectEventCatalogRegistry.listEvents();
    }

    @Override
    public EventMetadata getEvent(String eventCode) {
        return projectEventCatalogRegistry.getEvent(eventCode);
    }

    @Override
    public List<EventMetadata> getEventsForProject(String projectCode) {
        return projectEventCatalogRegistry.getEventsForProject(projectCode);
    }

    @Override
    public ProjectEventCatalog projectEventCatalog() {
        return projectEventCatalogRegistry;
    }

    @Override
    public void registerSubmitter(SubmitterRegistration submitterRegistration) {
        submitterRegistry.register(submitterRegistration);
    }

    @Override
    public List<SubmitterRegistration> listSubmitters() {
        return submitterRegistry.listSubmitters();
    }

    @Override
    public SubmitterRegistration getSubmitter(String principalId) {
        return submitterRegistry.getSubmitter(principalId);
    }

    @Override
    public TaskSubmitterContext authenticateSubmitter(String credential) {
        return submitterRegistry.authenticate(credential);
    }

    private void registerEnabledCatalogProjectsIntoCore() {
        for (ProjectMetadata projectMetadata : projectEventCatalogRegistry.listProjects()) {
            registerProjectIntoCore(projectMetadata);
        }
    }

    private void registerProjectIntoCore(ProjectMetadata projectMetadata) {
        Objects.requireNonNull(projectMetadata, "projectMetadata");
        ProjectRegistry.register(projectMetadata.getCode(), projectMetadata.getName(), projectMetadata.isEnabled());
    }

    @Override
    public List<Map<String, Object>> listDefaultRules() {
        return requireStartedRuleManager().getDefaultRules().stream()
                .sorted(Comparator.comparing(RuleDefinition::getId, Comparator.nullsLast(String::compareTo)))
                .map(this::toRuleItem)
                .toList();
    }

    @Override
    public List<String> listRuleTypes() {
        return List.of(RuleType.values()).stream().map(Enum::name).toList();
    }

    @Override
    public List<String> listRegisteredEvaluatorTypes() {
        return requireStartedRuleManager().getRegisteredEvaluatorTypes().stream().map(Enum::name).toList();
    }

    @Override
    public void replaceDefaultRules(Collection<RuleDefinition> rules) {
        Objects.requireNonNull(rules, "rules");
        RuleManager<Map<String, Object>> ruleManager = requireStartedRuleManager();
        ruleManager.clear();
        ruleManager.addDefaultRules(List.copyOf(rules));
    }

    @Override
    public void publishTaskEvents() {
        requireStartedEngine();
        delegate.publishTaskEvents();
    }

    @Override
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> data = new ArrayList<>();
        WorkerEndpointInspector sessionInspector = resolveSessionInspector();
        if (sessionInspector == null) {
            return data;
        }

        Map<String, List<WorkerEndpointSnapshot>> grouped = new HashMap<>();
        for (WorkerEndpointSnapshot snapshot : sessionInspector.listWorkerEndpoints()) {
            grouped.computeIfAbsent(snapshot.getWorkerId(), ignored -> new ArrayList<>()).add(snapshot);
        }
        grouped.forEach((workerId, endpoints) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("workerId", workerId);
            List<Map<String, Object>> roles = new ArrayList<>();
            endpoints.forEach(snapshot -> {
                Map<String, Object> roleInfo = new LinkedHashMap<>();
                roleInfo.put("role", snapshot.getEndpointRole());
                roleInfo.put("active", snapshot.isActive());
                roleInfo.put("endpointId", snapshot.getEndpointId());
                roleInfo.put("transport", snapshot.getTransport());
                roles.add(roleInfo);
            });
            entry.put("connections", roles);
            data.add(entry);
        });
        return data;
    }

    @Override
    public Map<String, Object> getSessionStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        WorkerEndpointRegistry sessionManager = resolveSessionManager();
        WorkerEndpointInspector sessionInspector = resolveSessionInspector();
        if (sessionManager != null) {
            data.put("activeConnections", sessionManager.getActiveConnectionCount());
            data.put("workerCount", sessionInspector != null
                    ? sessionInspector.listWorkerEndpoints().stream().map(WorkerEndpointSnapshot::getWorkerId).distinct().count()
                    : 0L);
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0L);
        }
        return data;
    }

    @Override
    public Map<String, Object> enqueueRawMessage(Map<String, Object> request) {
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext == null) {
            return Map.of("success", false, "msg", "transport context is not initialized");
        }
        MessageTransporter<?> messageTransporter = transportContext.getMessageTransporter();
        if (messageTransporter == null) {
            return Map.of("success", false, "msg", "message transporter is not initialized");
        }

        Envelope env = Envelope.builder()
                .rawJson(GSON.toJson(request))
                .receivedAt(System.currentTimeMillis())
                .build();
        transportContext.getMessageTransporter().sendOutput(env);
        return Map.of("success", true, "msg", "message enqueued");
    }

    @Override
    public Map<String, Object> getQueueDetail() {
        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        MessageTransporter<?> messageTransporter = transportContext != null ? transportContext.getMessageTransporter() : null;
        int inputSize = safeInputQueueSize(messageTransporter);
        int outputSize = safeOutputQueueSize(messageTransporter);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputQueue", inputSize);
        map.put("outputQueue", outputSize);
        map.put("inputQueueSize", inputSize);
        map.put("outputQueueSize", outputSize);
        map.put("transporterAvailable", messageTransporter != null);
        return map;
    }

    @Override
    public Map<String, Object> getQueueMetrics() {
        return Map.of(
                "inputQueueRate", 0,
                "outputQueueRate", 0
        );
    }

    @Override
    public List<?> getWorkerMessageHistory(String workerId) {
        return WorkerDebugMessageStore.getHistory(workerId);
    }

    @Override
    public Map<String, Object> sendWorkerMessage(String workerId,
                                                 String project,
                                                 String msgType,
                                                 String subMsgType,
                                                 Object payload) {
        Worker worker = requireStartedWorkerManager().getWorker(workerId);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found");
        }

        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext == null || transportContext.getMessageTransporter() == null) {
            throw new IllegalStateException("Message transporter is not initialized");
        }

        SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
        if (sessionContext == null || sessionContext.getSessionManager() == null) {
            throw new IllegalStateException("Session manager is not initialized");
        }

        WorkerEndpointRegistry sessionManager = sessionContext.getSessionManager();
        if (!sessionManager.isWorkerOnline(workerId, WorkerEndpointRoles.TASK_DISPATCH)) {
            throw new IllegalStateException("Target worker is offline or task dispatch endpoint is unavailable");
        }

        String resolvedProject = resolveProjectCode(project, worker);
        MessageType messageType = parseMessageType(msgType);
        JsonElement payloadJson = toPayloadJson(payload);
        String messageId = UUID.randomUUID().toString();
        String resolvedSubMsgType = resolveSubMsgType(subMsgType, messageType);
        JsonElement normalizedPayload = normalizePayload(payloadJson, workerId, messageId, messageType, resolvedSubMsgType);

        MassMessage message = new MassMessage();
        message.setMsgId(messageId);
        message.setMsgType(messageType);
        message.setSubMsgType(resolvedSubMsgType);
        message.setFrom(MessageDirection.SERVER);
        message.setProject(resolvedProject);
        message.setContext(buildMessageContext(workerId));
        message.setPayload(normalizedPayload);

        String rawJson = encodeMessage(message);
        Envelope envelope = Envelope.builder()
                .workerId(workerId)
                .connRole(WorkerEndpointRoles.TASK_DISPATCH)
                .project(resolvedProject)
                .traceId(messageId)
                .receivedAt(System.currentTimeMillis())
                .rawJson(rawJson)
                .build();
        WorkerDebugMessageStore.recordOutbound(
                workerId,
                resolvedProject,
                messageType.name(),
                resolvedSubMsgType,
                messageId,
                GSON.toJson(normalizedPayload),
                rawJson,
                "message queued to dispatcher"
        );
        transportContext.getMessageTransporter().sendOutput(envelope);
        return Map.of(
                "messageId", messageId,
                "workerId", workerId,
                "project", resolvedProject,
                "msgType", messageType.name(),
                "subMsgType", resolvedSubMsgType
        );
    }

    /**
     * @deprecated Mock/bootstrap loaders should be wired explicitly outside the SDK
     * core via {@link MassBootstrapDataProvider} and {@link MassRuntimeControl}.
     * This compatibility shim delegates to a configured provider when present.
     */
    @Deprecated(forRemoval = false)
    public void loadMockData() {
        requireStartedEngine();
        delegate.loadMockData();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. The underlying
     * runtime is exposed only as an escape hatch for advanced embedding.
     */
    @Deprecated(forRemoval = false)
    public MassApplication unwrap() {
        return delegate;
    }

    private void validateTaskCatalogContract(MassTaskRequest request) {
        Objects.requireNonNull(request, "request");
        ProjectMetadata projectMetadata = projectEventCatalogRegistry.getProject(request.getProject());
        if (projectMetadata == null) {
            throw new IllegalArgumentException("Unsupported SDK project: " + request.getProject());
        }
        if (!projectMetadata.isEnabled()) {
            throw new IllegalArgumentException("SDK project is disabled: " + request.getProject());
        }
        String eventCode = request.getEventCode();
        if (eventCode == null || eventCode.isBlank()) {
            return;
        }
        EventMetadata eventMetadata = projectEventCatalogRegistry.getEvent(eventCode);
        if (eventMetadata == null) {
            throw new IllegalArgumentException("Unsupported SDK event: " + eventCode);
        }
        if (!eventMetadata.isEnabled()) {
            throw new IllegalArgumentException("SDK event is disabled: " + eventCode);
        }
        if (!projectMetadata.getEventCodes().contains(eventCode)) {
            throw new IllegalArgumentException("SDK project " + request.getProject()
                    + " does not support event: " + eventCode);
        }
        if (!eventMetadata.getPayloadTypes().isEmpty()
                && !eventMetadata.getPayloadTypes().contains(request.getPayloadType())) {
            throw new IllegalArgumentException("SDK event " + eventCode
                    + " does not support payload type: " + request.getPayloadType());
        }
        if (!eventMetadata.getTaskModes().isEmpty()
                && !eventMetadata.getTaskModes().contains(request.getMode())) {
            throw new IllegalArgumentException("SDK event " + eventCode
                    + " does not support task mode: " + request.getMode());
        }
    }

    private TaskManager requireStartedTaskManager() {
        TaskManager taskManager = requireStartedEngine().getTaskManager();
        if (taskManager == null) {
            throw new IllegalStateException("Task manager is unavailable for this SDK application");
        }
        return taskManager;
    }

    private WorkerManager requireStartedWorkerManager() {
        WorkerManager workerManager = requireStartedEngine().getWorkerManager();
        if (workerManager == null) {
            throw new IllegalStateException("Worker manager is unavailable for this SDK application");
        }
        return workerManager;
    }

    private RuleManager<Map<String, Object>> requireStartedRuleManager() {
        RuleManager<Map<String, Object>> ruleManager = requireStartedEngine().getConfig().getRuleManager();
        if (ruleManager == null) {
            throw new IllegalStateException("Rule manager is unavailable for this SDK application");
        }
        return ruleManager;
    }

    private WorkerEndpointRegistry resolveSessionManager() {
        SessionContext ctx = DispatcherContextRegistry.getSessionContext();
        if (ctx == null || ctx.getSessionManager() == null) {
            return null;
        }
        return ctx.getSessionManager();
    }

    private WorkerEndpointInspector resolveSessionInspector() {
        WorkerEndpointRegistry sessionManager = resolveSessionManager();
        return sessionManager instanceof WorkerEndpointInspector inspector ? inspector : null;
    }

    private int safeInputQueueSize(MessageTransporter<?> messageTransporter) {
        try {
            return messageTransporter != null ? messageTransporter.inputQueueSize() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int safeOutputQueueSize(MessageTransporter<?> messageTransporter) {
        try {
            return messageTransporter != null ? messageTransporter.outputQueueSize() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private Map<String, Object> toRuleItem(RuleDefinition rule) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ruleId", rule.getId());
        item.put("name", rule.getName());
        item.put("type", rule.getType() != null ? rule.getType().name() : null);
        item.put("content", rule.getContent());
        item.put("description", rule.getDescription());
        item.put("enabled", rule.isEnabled());
        item.put("priority", rule.getPriority());
        return item;
    }

    private MessageContext buildMessageContext(String workerId) {
        MessageContext context = new MessageContext();
        context.setWorkerId(workerId);
        context.setConnRole(WorkerEndpointRoles.TASK_DISPATCH);
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

    private MessageType parseMessageType(String messageTypeText) {
        String text = defaultIfBlank(messageTypeText, MessageType.TASK.name());
        try {
            return MessageType.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported msgType: " + text);
        }
    }

    private String resolveSubMsgType(String subMsgType, MessageType messageType) {
        if (subMsgType != null && !subMsgType.isBlank()) {
            return subMsgType.trim();
        }
        if (messageType == MessageType.CONTROL) {
            return MANUAL_DEBUG_SUB_MSG_TYPE;
        }
        return "manual";
    }

    private JsonElement normalizePayload(JsonElement payload,
                                         String workerId,
                                         String messageId,
                                         MessageType messageType,
                                         String subMsgType) {
        if (messageType == MessageType.CONTROL && MANUAL_DEBUG_SUB_MSG_TYPE.equals(subMsgType)) {
            JsonObject normalized = payload != null && payload.isJsonObject()
                    ? payload.getAsJsonObject().deepCopy()
                    : new JsonObject();
            putIfMissing(normalized, "messageKind", "debug_chat");
            putIfMissing(normalized, "workerId", workerId);
            putIfMissing(normalized, "sentAt", System.currentTimeMillis());
            putIfMissing(normalized, "expectReply", true);
            putIfMissing(normalized, "clientMessageId", messageId);
            putIfMissing(normalized, "text", "");
            return normalized;
        }
        return payload;
    }

    private void putIfMissing(JsonObject payload, String field, String value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private void putIfMissing(JsonObject payload, String field, Number value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private void putIfMissing(JsonObject payload, String field, Boolean value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private String resolveProjectCode(String project, Worker worker) {
        if (project != null && !project.isBlank()) {
            return ProjectRegistry.require(project).getCode();
        }
        List<String> supportedProjects = worker.getSupportedProjects();
        if (supportedProjects != null && !supportedProjects.isEmpty()) {
            return ProjectRegistry.require(supportedProjects.get(0)).getCode();
        }
        return Project.DEMO_APP.getCode();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private MassEngine requireStartedEngine() {
        MassEngine engine = getEngine();
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this SDK application");
        }
        if (!engine.isRunning()) {
            throw new IllegalStateException("Mass engine has not been started");
        }
        return engine;
    }
}
