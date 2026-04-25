package com.xa.mass.sdk;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.command.event.*;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.auth.*;
import com.xa.mass.sdk.authz.*;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.PlatformEventCodes;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.event.EventDefinitionRegistry;
import com.xa.mass.sdk.event.EventHandler;
import com.xa.mass.sdk.model.*;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.*;

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
        ResourceOperations, AuthProvider,
        ExternalWorkerOperations,
        RuleOperations, TransportOperations {

    private static final Gson GSON = new Gson();

    private final MassApplication delegate;
    private final ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry;
    private final InMemorySubmitterRegistry submitterRegistry;
    private final InMemoryClientPermissionProvider clientPermissionProvider;
    private final InMemoryUserPermissionProvider userPermissionProvider;
    private final EventPermissionService eventPermissionService;
    private final MassEventRuntime eventRuntime;
    private final EventDefinitionRegistry eventDefinitionRegistry;
    private final Map<String, EventHandler> eventHandlerCache;
    private final ProjectEventCatalog sdkMetadataCatalogView;

    MassSdkApplication(MassApplication delegate) {
        this(delegate, DefaultProjectEventCatalogFactory.createDefaultProjectRegistry());
    }

    MassSdkApplication(MassApplication delegate, ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bootstrapProjectCatalogRegistry = Objects.requireNonNull(
                bootstrapProjectCatalogRegistry,
                "bootstrapProjectCatalogRegistry"
        );
        this.submitterRegistry = new InMemorySubmitterRegistry();
        this.clientPermissionProvider = new InMemoryClientPermissionProvider();
        this.userPermissionProvider = new InMemoryUserPermissionProvider();
        this.eventRuntime = delegate.getEventRuntime() != null ? delegate.getEventRuntime() : new InMemoryMassEventRuntime();
        this.eventDefinitionRegistry = new EventDefinitionRegistry();
        this.eventHandlerCache = new LinkedHashMap<>();
        this.sdkMetadataCatalogView = new DefinitionBackedProjectEventCatalog(
                this::listProjects,
                this::getProject,
                this::listEvents,
                this::getEvent,
                this::getEventsForProject
        );
        this.eventPermissionService = new DefaultEventPermissionService(
                clientPermissionProvider,
                userPermissionProvider,
                sdkMetadataCatalogView
        );
        registerEnabledCatalogProjectsIntoCore();
        registerCatalogEventDefinitions();
        registerControlPlaneEventHandlers();
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

    @Override
    public EventResponse dispatchEvent(EventRequest request, EventPrincipal principal) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision decision = eventPermissionService.authorize(principal, request);
        if (!decision.isAllowed()) {
            return EventResponse.failure("FORBIDDEN", decision.getReason(), request.getRequestId());
        }
        return dispatchEventInternal(request, principal);
    }

    public void grantClientEventPermissions(String clientId, Collection<String> eventCodes) {
        clientPermissionProvider.grant(clientId, eventCodes);
    }

    public void grantUserEventPermissions(String userId, Collection<String> eventCodes) {
        userPermissionProvider.grant(userId, eventCodes);
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
        validateTaskCatalogContract(request);
        if (request.getEventCode() == null || request.getEventCode().isBlank()) {
            return requireStartedEngine().createTask(MassTaskRequestMapper.toEngineRequest(request));
        }
        EventResponse response = dispatchEventInternal(
                EventRequest.builder()
                        .event(request.getEventCode())
                        .project(request.getProject())
                        .requestId(UUID.randomUUID().toString())
                        .payload(Map.of("request", request))
                        .build(),
                internalPrincipal(request.getUserId())
        );
        requireSuccessfulEventResponse(response);
        return (Task) response.getData();
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
        return booleanEvent(PlatformEventCodes.TASK_APPROVE, Map.of("taskId", taskId));
    }

    public boolean rejectTask(String taskId) {
        return booleanEvent(PlatformEventCodes.TASK_REJECT, Map.of("taskId", taskId));
    }

    public boolean blockTask(String taskId) {
        return booleanEvent(PlatformEventCodes.TASK_BLOCK, Map.of("taskId", taskId));
    }

    public boolean pauseTask(String taskId) {
        return booleanEvent(PlatformEventCodes.TASK_PAUSE, Map.of("taskId", taskId));
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
        return booleanEvent(PlatformEventCodes.TASK_RESUME, Map.of("taskId", taskId));
    }

    public boolean cancelTask(String taskId) {
        return booleanEvent(PlatformEventCodes.TASK_CANCEL, Map.of("taskId", taskId));
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return booleanEvent(PlatformEventCodes.TASK_TERMINATE, Map.of(
                "taskId", taskId,
                "reason", reason == null ? TaskTerminalReason.MANUAL_CANCELLED.name() : reason.name()
        ));
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.TASK_APPEND_ITEMS)
                .payload(Map.of("taskId", taskId, "inputs", inputs == null ? List.of() : inputs))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
        return ((Number) response.getData()).intValue();
    }

    public boolean sealTask(String taskId) {
        return booleanEvent(PlatformEventCodes.TASK_SEAL, Map.of("taskId", taskId));
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
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.WORKER_REGISTER)
                .payload(Map.of("request", request))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
    }

    @Override
    public void registerWorkerContext(WorkerContextRegistration request) {
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.WORKER_CONTEXT_REGISTER)
                .payload(Map.of("request", request))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
    }

    @Override
    public String getWorkerTransportHint(String workerId) {
        Worker worker = getWorker(requireWorkerId(workerId));
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + requireWorkerId(workerId));
        }
        String transportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (transportHint == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy is not set: " + worker.getWorkerId());
        }
        return transportHint;
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

    @Override
    public void workerOnline(String workerId, String reason) {
        externalPullWorkerSession(workerId).connect(reason);
    }

    @Override
    public void workerHeartbeat(String workerId, String reason) {
        externalPullWorkerSession(workerId).heartbeat(reason);
    }

    @Override
    public void workerOffline(String workerId, String reason) {
        externalPullWorkerSession(workerId).disconnect(reason);
    }

    @Override
    public List<TaskDispatchItem> pollTasks(String workerId, int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than 0");
        }
        return externalPullWorkerSession(workerId).poll(maxMessages);
    }

    @Override
    public boolean submitResult(String workerId, TaskResultReport report) {
        Objects.requireNonNull(report, "report");
        return externalPullWorkerSession(workerId).submitResult(
                report.getTaskId(),
                report.getMessageId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
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
        ProjectMetadata normalized = Objects.requireNonNull(projectMetadata, "projectMetadata");
        bootstrapProjectCatalogRegistry.registerProject(normalized);
        registerProjectIntoCore(normalized);
        syncProjectScopeIntoDefinitions(normalized);
    }

    @Override
    public void registerEventDefinition(EventDefinition definition) {
        registerEventDefinitionInternal(Objects.requireNonNull(definition, "definition"));
    }

    @Override
    public List<ProjectMetadata> listProjects() {
        return bootstrapProjectCatalogRegistry.listProjects();
    }

    @Override
    public ProjectMetadata getProject(String projectCode) {
        return bootstrapProjectCatalogRegistry.getProject(projectCode);
    }

    @Override
    public List<EventDefinition> listEvents() {
        return projectEventDefinitionsFromRuntime();
    }

    @Override
    public EventDefinition getEvent(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return null;
        }
        CoreEventDescriptor descriptor = eventRuntime.getDescriptor(eventCode.trim());
        return descriptor == null ? null : toEventDefinition(descriptor);
    }

    @Override
    public List<EventDefinition> getEventsForProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return List.of();
        }
        String normalizedProjectCode = projectCode.trim();
        if (getProject(normalizedProjectCode) == null) {
            return List.of();
        }
        return projectEventDefinitionsFromRuntime().stream()
                .filter(definition -> definition.getProjectCodes().contains(normalizedProjectCode))
                .toList();
    }

    @Override
    public SdkMetadataCatalog metadataCatalog() {
        return sdkMetadataCatalogView;
    }

    @Override
    public ProjectEventCatalog projectEventCatalog() {
        return sdkMetadataCatalogView;
    }

    @Override
    public void registerSubmitter(SubmitterRegistration submitterRegistration) {
        submitterRegistry.register(submitterRegistration);
    }

    @Override
    public List<SubmitterMetadata> listSubmitters() {
        return submitterRegistry.listSubmitters();
    }

    @Override
    public SubmitterMetadata getSubmitter(String principalId) {
        return submitterRegistry.getSubmitter(principalId);
    }

    @Override
    public TaskSubmitterContext authenticateSubmitter(String credential) {
        return submitterRegistry.authenticate(credential);
    }

    @Override
    public TaskSubmitterContext authenticate(String credential) {
        return authenticateSubmitter(credential);
    }

    private void registerControlPlaneEventHandlers() {
        registerPlatformEvent(
                PlatformEventCodes.WORKER_REGISTER,
                "Platform Worker Register",
                "Register a worker identity and capability record.",
                (request, principal) -> {
                    WorkerRegistration registration = resolveWorkerRegistration(request);
                    requireStartedEngine().addWorker(SdkResourceMapper.toWorker(registration));
                    return CoreEventResponse.success(Boolean.TRUE, request.getRequestId());
                }
        );
        registerPlatformEvent(
                PlatformEventCodes.WORKER_CONTEXT_REGISTER,
                "Platform Worker Context Register",
                "Register a worker execution context.",
                (request, principal) -> {
                    WorkerContextRegistration registration = resolveWorkerContextRegistration(request);
                    requireStartedEngine().addWorkerContext(SdkResourceMapper.toWorkerContext(registration));
                    return CoreEventResponse.success(Boolean.TRUE, request.getRequestId());
                }
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_APPROVE,
                "Platform Task Approve",
                "Approve a task and move it into scheduling.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().approveTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_REJECT,
                "Platform Task Reject",
                "Reject a task and block it before scheduling.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().rejectTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_BLOCK,
                "Platform Task Block",
                "Block an active or ready task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().blockTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_PAUSE,
                "Platform Task Pause",
                "Pause a running or ready task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().pauseTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_RESUME,
                "Platform Task Resume",
                "Resume a paused task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().resumeTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_CANCEL,
                "Platform Task Cancel",
                "Cancel a task and close it to terminal.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().cancelTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_TERMINATE,
                "Platform Task Terminate",
                "Terminate a task with an explicit terminal reason.",
                (request, principal) -> CoreEventResponse.success(
                                requireStartedTaskManager().terminateTask(
                                        readRequiredString(request.getPayload(), "taskId"),
                                TaskTerminalReason.valueOf(readString(request.getPayload(), "reason", TaskTerminalReason.MANUAL_CANCELLED.name()))
                        ),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_APPEND_ITEMS,
                "Platform Task Append Items",
                "Append more inputs to an open-intake task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().appendTaskItems(
                                readRequiredString(request.getPayload(), "taskId"),
                                readInputMaps(request.getPayload().get("inputs"))
                        ),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_SEAL,
                "Platform Task Seal",
                "Seal an open-intake task against further appends.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskManager().sealTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.META_PROJECTS_LIST,
                "Platform Meta Projects List",
                "List registered SDK projects.",
                (request, principal) -> CoreEventResponse.success(listProjects(), request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.META_PROJECT_GET,
                "Platform Meta Project Get",
                "Get a single registered SDK project.",
                (request, principal) -> CoreEventResponse.success(
                        getProject(resolveProjectCodeForMeta(request)),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.META_PROJECT_EVENTS_LIST,
                "Platform Meta Project Events List",
                "List task events supported by a project.",
                (request, principal) -> CoreEventResponse.success(
                        getEventsForProject(resolveProjectCodeForMeta(request)),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.META_EVENTS_LIST,
                "Platform Meta Events List",
                "List registered SDK events.",
                (request, principal) -> CoreEventResponse.success(listEvents(), request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.META_EVENT_GET,
                "Platform Meta Event Get",
                "Get a single registered SDK event.",
                (request, principal) -> CoreEventResponse.success(
                        getEvent(resolveEventCodeForMeta(request)),
                        request.getRequestId())
        );
    }

    private void registerPlatformEvent(String eventCode,
                                       String name,
                                       String description,
                                       com.xa.mass.command.event.MassEventHandler handler) {
        EventDefinition existing = getEvent(eventCode);
        registerEventDefinitionInternal(EventDefinition.builder()
                .code(eventCode)
                .name(existing != null ? existing.getName() : name)
                .description(existing != null ? existing.getDescription() : description)
                .payloadTypes(existing != null ? existing.getPayloadTypes() : List.of(PayloadType.JSON))
                .taskModes(existing != null ? existing.getTaskModes() : List.of())
                .enabled(existing == null || existing.isEnabled())
                .defaultRoutingCode(existing != null ? existing.getDefaultRoutingCode() : null)
                .projectCodes(existing != null ? existing.getProjectCodes() : List.of())
                .handler((request, principal) -> toSdkResponse(
                        handler.handle(toCoreRequest(request), toCorePrincipal(principal))
                ))
                .build());
    }

    private EventResponse dispatchEventInternal(EventRequest request, EventPrincipal principal) {
        try {
            return toSdkResponse(eventRuntime.dispatch(toCoreRequest(request), toCorePrincipal(principal)));
        } catch (IllegalArgumentException e) {
            return EventResponse.failure("BAD_REQUEST", e.getMessage(), request.getRequestId());
        } catch (IllegalStateException e) {
            return EventResponse.failure("ILLEGAL_STATE", e.getMessage(), request.getRequestId());
        } catch (Exception e) {
            return EventResponse.failure("ERROR", e.getMessage(), request.getRequestId());
        }
    }

    private EventRequest toSdkRequest(CoreEventRequest request) {
        if (request == null) {
            return EventRequest.builder().event("unknown").build();
        }
        return EventRequest.builder()
                .event(request.getEvent())
                .project(request.getProject())
                .requestId(request.getRequestId())
                .payload(request.getPayload())
                .headers(request.getHeaders())
                .build();
    }

    private EventPrincipal toSdkPrincipal(CoreEventPrincipal principal) {
        if (principal == null) {
            return EventPrincipal.builder().build();
        }
        return EventPrincipal.builder()
                .clientId(principal.clientId())
                .userId(principal.userId())
                .build();
    }

    private CoreEventResponse toCoreResponse(EventResponse response) {
        if (response == null) {
            return CoreEventResponse.failure("ERROR", "event handler returned null", null);
        }
        if (response.isSuccess()) {
            return CoreEventResponse.success(response.getData(), response.getRequestId());
        }
        return CoreEventResponse.failure(
                response.getCode() == null ? "ERROR" : response.getCode(),
                response.getMessage(),
                response.getRequestId()
        );
    }

    private void registerEventDefinitionInternal(EventDefinition definition) {
        EventDefinition normalized = Objects.requireNonNull(definition, "definition");
        EventDefinition merged = EventDefinition.builder()
                .code(normalized.getCode())
                .name(normalized.getName())
                .description(normalized.getDescription())
                .payloadTypes(normalized.getPayloadTypes())
                .taskModes(normalized.getTaskModes())
                .enabled(normalized.isEnabled())
                .defaultRoutingCode(normalized.getDefaultRoutingCode())
                .projectCodes(mergeProjectCodes(resolveProjectCodesForEvent(normalized.getCode()), normalized.getProjectCodes()))
                .handler(resolveDefinitionHandler(normalized))
                .build();
        eventHandlerCache.put(merged.getCode(), merged.getHandler());
        eventRuntime.registerOrReplace(toCoreDescriptor(merged), toCoreHandler(merged.getHandler()));
        refreshDerivedEventDefinitionCache();
    }

    private EventHandler existingHandler(String eventCode) {
        return eventHandlerCache.get(eventCode);
    }

    private void syncProjectScopeIntoDefinitions(ProjectMetadata projectMetadata) {
        if (projectMetadata.getEventCodes() == null || projectMetadata.getEventCodes().isEmpty()) {
            return;
        }
        for (String eventCode : projectMetadata.getEventCodes()) {
            EventDefinition existing = getEvent(eventCode);
            if (existing == null) {
                continue;
            }
            EventHandler existingHandler = existingHandler(existing.getCode());
            if (existingHandler == null) {
                continue;
            }
            eventRuntime.registerOrReplace(
                    toCoreDescriptor(EventDefinition.builder()
                            .code(existing.getCode())
                            .name(existing.getName())
                            .description(existing.getDescription())
                            .payloadTypes(existing.getPayloadTypes())
                            .taskModes(existing.getTaskModes())
                            .enabled(existing.isEnabled())
                            .defaultRoutingCode(existing.getDefaultRoutingCode())
                            .projectCodes(mergeProjectCodes(existing.getProjectCodes(), List.of(projectMetadata.getCode())))
                            .handler(existingHandler)
                            .build()),
                    toCoreHandler(existingHandler)
            );
        }
        refreshDerivedEventDefinitionCache();
    }

    private List<String> resolveProjectCodesForEvent(String eventCode) {
        LinkedHashSet<String> projectCodes = new LinkedHashSet<>();
        CoreEventDescriptor existing = eventRuntime.getDescriptor(eventCode);
        if (existing != null) {
            projectCodes.addAll(existing.getProjectCodes());
        }
        for (ProjectMetadata projectMetadata : bootstrapProjectCatalogRegistry.listProjects()) {
            if (projectMetadata.getEventCodes().contains(eventCode)) {
                projectCodes.add(projectMetadata.getCode());
            }
        }
        return List.copyOf(projectCodes);
    }

    private List<String> mergeProjectCodes(List<String> left, List<String> right) {
        LinkedHashSet<String> projectCodes = new LinkedHashSet<>();
        if (left != null) {
            projectCodes.addAll(left);
        }
        if (right != null) {
            projectCodes.addAll(right);
        }
        return List.copyOf(projectCodes);
    }

    private EventHandler resolveDefinitionHandler(EventDefinition definition) {
        if (definition.getHandler() != null) {
            return definition.getHandler();
        }
        return (request, principal) -> dispatchCatalogTaskEvent(request, principal, definition);
    }

    private EventResponse dispatchCatalogTaskEvent(EventRequest request,
                                                   EventPrincipal principal,
                                                   EventDefinition definition) {
        MassTaskRequest taskRequest = resolveTaskRequest(request, principal, definition);
        validateTaskCatalogContract(taskRequest);
        Task task = requireStartedEngine().createTask(MassTaskRequestMapper.toEngineRequest(taskRequest));
        return EventResponse.success(task, request.getRequestId());
    }

    private MassTaskRequest resolveTaskRequest(EventRequest request,
                                               EventPrincipal principal,
                                               EventDefinition definition) {
        Object embeddedRequest = request.getPayload().get("request");
        if (embeddedRequest instanceof MassTaskRequest massTaskRequest) {
            return massTaskRequest;
        }

        Map<String, Object> payload = request.getPayload();
        Map<String, String> headers = request.getHeaders();
        TaskMode mode = parseTaskMode(headers.get("taskMode"), definition);
        PayloadType payloadType = parsePayloadType(headers.get("payloadType"), definition);
        MassTaskRequest.Builder builder = MassTaskRequest.builder()
                .userId(firstNonBlank(headers.get("userId"), principal == null ? null : principal.getUserId()))
                .project(request.getProject())
                .taskName(firstNonBlank(headers.get("taskName"), request.getEvent().value()))
                .eventCode(request.getEvent().value())
                .mode(mode)
                .payloadType(payloadType)
                .sharedConfig(resolveSharedConfig(payload, headers))
                .batchSize(readInt(headers.get("batchSize"), 1))
                .defaultMsgMaxRetryCount(readInt(headers.get("defaultMsgMaxRetryCount"), 3))
                .maxRuntimeSeconds(readInt(headers.get("maxRuntimeSeconds"), 0));

        if (payloadType == PayloadType.TEXT) {
            builder.inputs(resolveTextInputs(payload));
        } else {
            builder.inputs(resolveJsonInputs(payload));
        }
        return builder.build();
    }

    private Map<String, Object> resolveSharedConfig(Map<String, Object> payload, Map<String, String> headers) {
        Map<String, Object> sharedConfig = readMap(payload.get("sharedConfig"));
        if (sharedConfig.isEmpty()) {
            sharedConfig = new LinkedHashMap<>();
        } else {
            sharedConfig = new LinkedHashMap<>(sharedConfig);
        }
        String routingCode = headers.get("routingCode");
        if (routingCode != null && !routingCode.isBlank()) {
            sharedConfig.put("routingCode", routingCode.trim());
        }
        return sharedConfig;
    }

    private List<com.xa.mass.sdk.model.MassInput> resolveTextInputs(Map<String, Object> payload) {
        Object texts = payload.get("texts");
        if (texts instanceof List<?> values && !values.isEmpty()) {
            List<com.xa.mass.sdk.model.MassInput> inputs = new ArrayList<>(values.size());
            for (Object value : values) {
                inputs.add(new TextInput(value == null ? "" : String.valueOf(value)));
            }
            return inputs;
        }
        Object text = payload.get("text");
        if (text != null) {
            return List.of(new TextInput(String.valueOf(text)));
        }
        return List.of(new TextInput(""));
    }

    private List<com.xa.mass.sdk.model.MassInput> resolveJsonInputs(Map<String, Object> payload) {
        Object rawInputs = payload.get("inputs");
        if (rawInputs instanceof List<?> values && !values.isEmpty()) {
            List<com.xa.mass.sdk.model.MassInput> inputs = new ArrayList<>(values.size());
            for (Object value : values) {
                inputs.add(new JsonInput(readMap(value)));
            }
            return inputs;
        }
        Map<String, Object> input = new LinkedHashMap<>(payload);
        input.remove("sharedConfig");
        input.remove("inputs");
        input.remove("request");
        if (input.isEmpty()) {
            input = Map.of();
        }
        return List.of(new JsonInput(input));
    }

    private WorkerRegistration resolveWorkerRegistration(CoreEventRequest request) {
        Object embedded = request.getPayload().get("request");
        if (embedded instanceof WorkerRegistration registration) {
            return normalizeWorkerRegistration(registration);
        }
        Map<String, Object> payload = request.getPayload();
        return normalizeWorkerRegistration(WorkerRegistration.builder()
                .workerId(readRequiredString(payload, "workerId"))
                .workerGroupId(readString(payload, "workerGroupId", null))
                .supportedProjects(readStringList(payload.get("supportedProjects")))
                .supportedEventCodes(readStringList(payload.get("supportedEventCodes")))
                .eventBindings(readWorkerEventBindings(payload.get("eventBindings")))
                .transportHint(readString(payload, "transportHint", null))
                .attributes(readStringMap(payload.get("attributes")))
                .build());
    }

    private WorkerContextRegistration resolveWorkerContextRegistration(CoreEventRequest request) {
        Object embedded = request.getPayload().get("request");
        if (embedded instanceof WorkerContextRegistration registration) {
            return registration;
        }
        Map<String, Object> payload = request.getPayload();
        return WorkerContextRegistration.builder()
                .workerContextId(readRequiredString(payload, "workerContextId"))
                .workerId(readRequiredString(payload, "workerId"))
                .project(readString(payload, "project", null))
                .routingTags(Set.copyOf(readStringList(payload.get("routingTags"))))
                .attributes(readStringMap(payload.get("attributes")))
                .build();
    }

    private String resolveProjectCodeForMeta(CoreEventRequest request) {
        return firstNonBlank(readString(request.getPayload(), "projectCode", null), request.getProject());
    }

    private String resolveEventCodeForMeta(CoreEventRequest request) {
        return readString(request.getPayload(), "eventCode", null);
    }

    private EventResponse toSdkResponse(CoreEventResponse response) {
        return EventResponse.builder()
                .success(response.isSuccess())
                .code(response.getCode())
                .message(response.getMessage())
                .data(response.getData())
                .requestId(response.getRequestId())
                .build();
    }

    private CoreEventRequest toCoreRequest(EventRequest request) {
        return CoreEventRequest.builder()
                .event(request.getEvent().value())
                .project(request.getProject())
                .payload(request.getPayload())
                .headers(request.getHeaders())
                .requestId(request.getRequestId())
                .build();
    }

    private CoreEventPrincipal toCorePrincipal(EventPrincipal principal) {
        return new CoreEventPrincipal(
                principal == null ? null : principal.getClientId(),
                principal == null ? null : principal.getUserId()
        );
    }

    private boolean booleanEvent(String eventCode, Map<String, Object> payload) {
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(eventCode)
                .payload(payload)
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
        return Boolean.TRUE.equals(response.getData());
    }

    private void requireSuccessfulEventResponse(EventResponse response) {
        if (response != null && response.isSuccess()) {
            return;
        }
        String code = response == null ? null : response.getCode();
        String message = response == null ? "event dispatch failed" : response.getMessage();
        if ("BAD_REQUEST".equalsIgnoreCase(code)) {
            throw new IllegalArgumentException(message);
        }
        if ("FORBIDDEN".equalsIgnoreCase(code)) {
            throw new SecurityException(message);
        }
        throw new IllegalStateException(message);
    }

    private EventPrincipal internalPrincipal(String userId) {
        return EventPrincipal.builder()
                .clientId("sdk-internal")
                .userId(userId)
                .build();
    }

    private TaskMode parseTaskMode(String rawValue, EventDefinition definition) {
        if (rawValue != null && !rawValue.isBlank()) {
            return TaskMode.valueOf(rawValue.trim().toUpperCase());
        }
        if (definition != null && !definition.getTaskModes().isEmpty()) {
            return definition.getTaskModes().get(0);
        }
        return TaskMode.SINGLE_RUN;
    }

    private PayloadType parsePayloadType(String rawValue, EventDefinition definition) {
        if (rawValue != null && !rawValue.isBlank()) {
            return PayloadType.valueOf(rawValue.trim().toUpperCase());
        }
        if (definition != null && !definition.getPayloadTypes().isEmpty()) {
            return definition.getPayloadTypes().get(0);
        }
        return PayloadType.JSON;
    }

    private String readRequiredString(Map<String, Object> payload, String field) {
        String value = readString(payload, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private String readString(Map<String, Object> payload, String field, String defaultValue) {
        Object value = payload.get(field);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int readInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, innerValue) -> normalized.put(String.valueOf(key), innerValue));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, String> readStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        map.forEach((key, innerValue) -> {
            if (key != null && innerValue != null) {
                normalized.put(String.valueOf(key).trim(), String.valueOf(innerValue).trim());
            }
        });
        return normalized;
    }

    private List<WorkerEventBinding> readWorkerEventBindings(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<WorkerEventBinding> bindings = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> bindingMap = readMap(item);
            if (bindingMap.isEmpty()) {
                continue;
            }
            bindings.add(WorkerEventBinding.builder()
                    .eventCode(readRequiredString(bindingMap, "eventCode"))
                    .projectCodes(readStringList(bindingMap.get("projectCodes")))
                    .build());
        }
        return List.copyOf(bindings);
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<Map<String, Object>> readInputMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> inputs = new ArrayList<>(list.size());
        for (Object item : list) {
            inputs.add(readMap(item));
        }
        return List.copyOf(inputs);
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left.trim();
        }
        if (right != null && !right.isBlank()) {
            return right.trim();
        }
        return null;
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }

    private PullWorkerSession externalPullWorkerSession(String workerId) {
        return pullWorker(requireWorkerId(workerId));
    }

    private WorkerRegistration normalizeWorkerRegistration(WorkerRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        List<WorkerEventBinding> bindings = registration.getEventBindings();
        if (bindings == null || bindings.isEmpty()) {
            return registration;
        }

        LinkedHashSet<String> supportedEventCodes = new LinkedHashSet<>();
        LinkedHashSet<String> supportedProjects = new LinkedHashSet<>();
        for (WorkerEventBinding binding : bindings) {
            EventDefinition definition = requireEnabledEventDefinition(binding.getEventCode());
            supportedEventCodes.add(definition.getCode());
            supportedProjects.addAll(resolveWorkerBindingProjects(definition, binding));
        }

        return WorkerRegistration.builder()
                .workerId(registration.getWorkerId())
                .workerGroupId(registration.getWorkerGroupId())
                .supportedProjects(List.copyOf(supportedProjects))
                .supportedEventCodes(List.copyOf(supportedEventCodes))
                .eventBindings(bindings)
                .transportHint(registration.getTransportHint())
                .attributes(registration.getAttributes())
                .build();
    }

    private EventDefinition requireEnabledEventDefinition(String eventCode) {
        EventDefinition definition = getEvent(eventCode);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported worker event: " + eventCode);
        }
        if (!definition.isEnabled()) {
            throw new IllegalArgumentException("Worker event is disabled: " + eventCode);
        }
        return definition;
    }

    private List<String> resolveWorkerBindingProjects(EventDefinition definition, WorkerEventBinding binding) {
        List<String> definitionScope = definition.getProjectCodes();
        if (binding.getProjectCodes() == null || binding.getProjectCodes().isEmpty()) {
            return definitionScope;
        }
        List<String> resolvedProjects = new ArrayList<>(binding.getProjectCodes().size());
        for (String projectCode : binding.getProjectCodes()) {
            ProjectMetadata projectMetadata = getProject(projectCode);
            if (projectMetadata == null) {
                throw new IllegalArgumentException("Unsupported worker project: " + projectCode);
            }
            if (!projectMetadata.isEnabled()) {
                throw new IllegalArgumentException("Worker project is disabled: " + projectCode);
            }
            if (!definitionScope.contains(projectMetadata.getCode())) {
                throw new IllegalArgumentException("Worker project " + projectMetadata.getCode()
                        + " is outside event scope: " + definition.getCode());
            }
            resolvedProjects.add(projectMetadata.getCode());
        }
        return List.copyOf(resolvedProjects);
    }

    private void registerEnabledCatalogProjectsIntoCore() {
        for (ProjectMetadata projectMetadata : bootstrapProjectCatalogRegistry.listProjects()) {
            registerProjectIntoCore(projectMetadata);
        }
    }

    private void registerCatalogEventDefinitions() {
        for (EventDefinition definition : bootstrapProjectCatalogRegistry.listEvents()) {
            registerEventDefinitionInternal(definition);
        }
    }

    private CoreEventDescriptor toCoreDescriptor(EventDefinition definition) {
        return CoreEventDescriptor.builder()
                .event(definition.getCode())
                .name(definition.getName())
                .summary(definition.getDescription())
                .description(definition.getDescription())
                .payloadTypes(definition.getPayloadTypes().stream().map(Enum::name).toList())
                .taskModes(definition.getTaskModes().stream().map(Enum::name).toList())
                .defaultRoutingCode(definition.getDefaultRoutingCode())
                .projectCodes(definition.getProjectCodes())
                .enabled(definition.isEnabled())
                .build();
    }

    private MassEventHandler toCoreHandler(EventHandler handler) {
        if (handler == null) {
            return null;
        }
        return (request, principal) -> {
            try {
                return toCoreResponse(handler.handle(toSdkRequest(request), toSdkPrincipal(principal)));
            } catch (IllegalArgumentException e) {
                return CoreEventResponse.failure("BAD_REQUEST", e.getMessage(), request.getRequestId());
            } catch (SecurityException e) {
                return CoreEventResponse.failure("FORBIDDEN", e.getMessage(), request.getRequestId());
            } catch (Exception e) {
                return CoreEventResponse.failure("ERROR", e.getMessage(), request.getRequestId());
            }
        };
    }

    private void refreshDerivedEventDefinitionCache() {
        eventDefinitionRegistry.replaceAll(projectEventDefinitionsFromRuntime());
    }

    private EventDefinition toEventDefinition(CoreEventDescriptor descriptor) {
        return EventDefinition.builder()
                .code(descriptor.getEvent())
                .name(firstNonBlank(descriptor.getName(), descriptor.getEvent()))
                .description(firstNonBlank(descriptor.getDescription(), descriptor.getSummary()))
                .payloadTypes(parsePayloadTypes(descriptor.getPayloadTypes()))
                .taskModes(parseTaskModes(descriptor.getTaskModes()))
                .enabled(descriptor.isEnabled())
                .defaultRoutingCode(descriptor.getDefaultRoutingCode())
                .projectCodes(resolveProjectCodesForEvent(descriptor.getEvent(), descriptor.getProjectCodes()))
                .handler(eventHandlerCache.get(descriptor.getEvent()))
                .build();
    }

    private List<String> resolveProjectCodesForEvent(String eventCode, Collection<String> seedProjectCodes) {
        LinkedHashSet<String> projectCodes = new LinkedHashSet<>();
        if (seedProjectCodes != null) {
            projectCodes.addAll(seedProjectCodes);
        }
        for (ProjectMetadata projectMetadata : bootstrapProjectCatalogRegistry.listProjects()) {
            if (projectMetadata.getEventCodes().contains(eventCode)) {
                projectCodes.add(projectMetadata.getCode());
            }
        }
        return List.copyOf(projectCodes);
    }

    private List<PayloadType> parsePayloadTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<PayloadType> payloadTypes = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                payloadTypes.add(PayloadType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Keep derived SDK metadata tolerant of unknown internal descriptor values.
            }
        }
        return List.copyOf(payloadTypes);
    }

    private List<TaskMode> parseTaskModes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<TaskMode> taskModes = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                taskModes.add(TaskMode.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Keep derived SDK metadata tolerant of unknown internal descriptor values.
            }
        }
        return List.copyOf(taskModes);
    }

    private List<EventDefinition> projectEventDefinitionsFromRuntime() {
        return eventRuntime.listDescriptors().stream()
                .map(this::toEventDefinition)
                .toList();
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
        WorkerEndpointInspector endpointInspector = resolveEndpointInspector();
        if (endpointInspector == null) {
            return data;
        }

        Map<String, List<WorkerEndpointSnapshot>> grouped = new HashMap<>();
        for (WorkerEndpointSnapshot snapshot : endpointInspector.listWorkerEndpoints()) {
            grouped.computeIfAbsent(snapshot.getWorkerId(), ignored -> new ArrayList<>()).add(snapshot);
        }
        grouped.forEach((workerId, endpoints) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("workerId", workerId);
            List<Map<String, Object>> connections = new ArrayList<>();
            endpoints.forEach(snapshot -> {
                Map<String, Object> connectionInfo = new LinkedHashMap<>();
                connectionInfo.put("active", snapshot.isActive());
                connectionInfo.put("endpointId", snapshot.getEndpointId());
                connectionInfo.put("transport", snapshot.getTransport());
                connections.add(connectionInfo);
            });
            entry.put("connections", connections);
            data.add(entry);
        });
        return data;
    }

    @Override
    public Map<String, Object> getSessionStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        WorkerEndpointInspector endpointInspector = resolveEndpointInspector();
        if (endpointRegistry != null) {
            data.put("activeConnections", endpointRegistry.getActiveConnectionCount());
            data.put("workerCount", endpointInspector != null
                    ? endpointInspector.listWorkerEndpoints().stream().map(WorkerEndpointSnapshot::getWorkerId).distinct().count()
                    : 0L);
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0L);
        }
        return data;
    }

    @Override
    public Map<String, Object> enqueueRawMessage(Map<String, Object> request) {
        MessageTransporter<?, ?> messageTransporter = delegate.getMessageTransporter();
        if (messageTransporter == null) {
            return Map.of("success", false, "msg", "message transporter is not initialized");
        }
        Object workerId = request.get("workerId");
        if (!(workerId instanceof String workerIdText) || workerIdText.isBlank()) {
            return Map.of("success", false, "msg", "workerId is required");
        }
        Object rawJson = request.get("rawJson");
        String payload = rawJson instanceof String rawText ? rawText : GSON.toJson(request);
        delegate.getMessageTransporter().sendOutput(new OutboundDelivery(
                workerIdText.trim(),
                payload,
                UUID.randomUUID().toString()
        ));
        return Map.of("success", true, "msg", "message enqueued");
    }

    @Override
    public Map<String, Object> getQueueDetail() {
        MessageTransporter<?, ?> messageTransporter = delegate.getMessageTransporter();
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
        ProjectMetadata projectMetadata = bootstrapProjectCatalogRegistry.getProject(request.getProject());
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
        EventDefinition definition = getEvent(eventCode);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported SDK event: " + eventCode);
        }
        if (!definition.isEnabled()) {
            throw new IllegalArgumentException("SDK event is disabled: " + eventCode);
        }
        if (definition.getProjectCodes().isEmpty() || !definition.getProjectCodes().contains(request.getProject())) {
            throw new IllegalArgumentException("SDK project " + request.getProject()
                    + " does not support event: " + eventCode);
        }
        if (!definition.getPayloadTypes().isEmpty()
                && !definition.getPayloadTypes().contains(request.getPayloadType())) {
            throw new IllegalArgumentException("SDK event " + eventCode
                    + " does not support payload type: " + request.getPayloadType());
        }
        if (!definition.getTaskModes().isEmpty()
                && !definition.getTaskModes().contains(request.getMode())) {
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

    private WorkerEndpointRegistry resolveEndpointRegistry() {
        return delegate.getEndpointRegistry();
    }

    private WorkerEndpointInspector resolveEndpointInspector() {
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        return endpointRegistry instanceof WorkerEndpointInspector inspector ? inspector : null;
    }

    private int safeInputQueueSize(MessageTransporter<?, ?> messageTransporter) {
        try {
            return messageTransporter != null ? messageTransporter.inputQueueSize() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int safeOutputQueueSize(MessageTransporter<?, ?> messageTransporter) {
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
