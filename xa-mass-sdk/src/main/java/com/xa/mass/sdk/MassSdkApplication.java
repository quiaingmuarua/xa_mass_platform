package com.xa.mass.sdk;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.command.event.*;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskMessageLogicallyFinalListener;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.sdk.auth.*;
import com.xa.mass.sdk.authz.*;
import com.xa.mass.sdk.catalog.*;
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
 * runtime, but the stable embedding path stays on {@code com.xa.mass.sdk.*}
 * methods rather than exposing starter/runtime internals directly.
 */
public final class MassSdkApplication implements MassRuntimeControl, TaskQueryOperations, TaskAdminOperations,
        WorkerQueryOperations, WorkerAdminOperations,
        ResourceOperations, AuthProvider, PrincipalDirectory,
        ExternalWorkerOperations, AuthorizationPolicy,
        RuleOperations, TransportOperations {

    private static final Gson GSON = new Gson();

    private final MassApplication delegate;
    private final ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry;
    private final SubmitterRegistry submitterRegistry;
    private final EventPermissionService eventPermissionService;
    private final AuthorizationPolicy authorizationPolicy;
    private final MassEventRuntime eventRuntime;
    private final EventDefinitionRegistry eventDefinitionRegistry;
    private final Map<String, EventHandler> eventHandlerCache;
    private final ProjectEventCatalog sdkMetadataCatalogView;

    MassSdkApplication(MassApplication delegate) {
        this(delegate, DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(), new InMemorySubmitterRegistry());
    }

    MassSdkApplication(MassApplication delegate, ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry) {
        this(delegate, bootstrapProjectCatalogRegistry, new InMemorySubmitterRegistry());
    }

    MassSdkApplication(MassApplication delegate, SubmitterRegistry submitterRegistry) {
        this(delegate, DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(), submitterRegistry);
    }

    MassSdkApplication(MassApplication delegate,
                       ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry,
                       SubmitterRegistry submitterRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bootstrapProjectCatalogRegistry = Objects.requireNonNull(
                bootstrapProjectCatalogRegistry,
                "bootstrapProjectCatalogRegistry"
        );
        this.submitterRegistry = Objects.requireNonNull(submitterRegistry, "submitterRegistry");
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
        this.eventPermissionService = new DefaultEventPermissionService(sdkMetadataCatalogView);
        this.authorizationPolicy = new DefaultAuthorizationPolicy();
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
    public EventResponse dispatchEvent(EventRequest request, PrincipalContext principal) {
        Objects.requireNonNull(request, "request");
        AuthorizationDecision decision = eventPermissionService.authorize(principal, request);
        if (!decision.isAllowed()) {
            return EventResponse.failure("FORBIDDEN", decision.getReason(), request.getRequestId());
        }
        return dispatchEventInternal(request, principal);
    }

    @Override
    public Task createTask(MassTaskCreateRequest request) {
        MassEngine engine = requireStartedEngine();
        return engine.createTask(SdkResourceMapper.toEngineRequest(
                TaskOwnershipSupport.stamp(request, internalPrincipal(request.getUserId()))
        ));
    }

    @Override
    public Task createTask(MassTaskRequest request) {
        MassTaskRequest stampedRequest = TaskOwnershipSupport.stamp(request, internalPrincipal(request.getUserId()));
        validateTaskCatalogContract(stampedRequest);
        if (stampedRequest.getEventCode() == null || stampedRequest.getEventCode().isBlank()) {
            return requireStartedEngine().createTask(MassTaskRequestMapper.toEngineRequest(stampedRequest));
        }
        EventResponse response = dispatchEventInternal(
                EventRequest.builder()
                        .event(stampedRequest.getEventCode())
                        .project(stampedRequest.getProject())
                        .requestId(UUID.randomUUID().toString())
                        .payload(Map.of("request", stampedRequest))
                        .build(),
                internalPrincipal(stampedRequest.getUserId())
        );
        requireSuccessfulEventResponse(response);
        return (Task) response.getData();
    }

    public Task getTask(String taskId) {
        return requireStartedTaskQueries().getTask(taskId);
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return requireStartedTaskQueries().listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return requireStartedTaskQueries().getTasksByStatus(status);
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
        TaskResumeResult result = requireStartedTaskCommands().resumeTaskDetailed(taskId);
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

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return requireStartedTaskQueries().resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return requireStartedTaskQueries().validateTaskState(taskId);
    }

    @Override
    public SdkTaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        String normalizedTaskId = requireTaskId(taskId);
        int boundedLimit = Math.max(0, limit);
        TaskDetailStore taskDetailStore = requireStartedTaskDetailStore();
        List<SdkTaskMessageView> messages = taskDetailStore.getTaskMessages(normalizedTaskId, boundedLimit).stream()
                .map(this::toSdkTaskMessageView)
                .toList();
        boolean truncated = boundedLimit > 0 && taskDetailStore.countTaskMessages(normalizedTaskId) > messages.size();
        return new SdkTaskMessageSnapshot(messages, boundedLimit, truncated);
    }

    @Override
    public SdkTaskMessageView getTaskMessageView(String taskId, String messageId) {
        return requireStartedTaskDetailStore()
                .getTaskMessage(requireTaskId(taskId), requireMessageId(messageId))
                .map(this::toSdkTaskMessageView)
                .orElse(null);
    }

    @Override
    public List<SdkTaskMessageAttemptView> getTaskMessageAttemptViews(String taskId, String messageId) {
        return requireStartedTaskDetailStore()
                .getTaskMessageAttempts(requireTaskId(taskId), requireMessageId(messageId))
                .stream()
                .map(this::toSdkTaskMessageAttemptView)
                .toList();
    }

    @Override
    public SdkTaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId) {
        return requireStartedTaskDetailStore()
                .getLatestActiveTaskMessageAttempt(requireTaskId(taskId), requireMessageId(messageId))
                .map(this::toSdkTaskMessageAttemptView)
                .orElse(null);
    }

    @Override
    public boolean updateTaskDefinition(String taskId, MassTaskUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        Task task = requireStartedTaskQueries().getTask(requireTaskId(taskId));
        if (task == null) {
            return false;
        }
        if (request.getTaskName() != null) {
            task.setTaskName(request.getTaskName());
        }
        if (request.getProject() != null) {
            task.setProject(request.getProject());
        }
        if (request.getSharedConfig() != null) {
            task.setSharedConfig(request.getSharedConfig());
        }
        if (request.getUserId() != null) {
            task.setUser(UserRef.of(request.getUserId()));
        }
        if (request.getBatchSize() != null && request.getBatchSize() > 0) {
            task.setBatchSize(request.getBatchSize());
        }
        return requireStartedTaskCommands().updateTask(task);
    }

    @Override
    public boolean deleteTask(String taskId) {
        return requireStartedTaskCommands().deleteTask(taskId);
    }

    @Override
    public void registerWorker(WorkerRegistration request) {
        requireStartedEngine();
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.WORKER_REGISTER)
                .payload(Map.of("request", request))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
    }

    @Override
    public void registerWorkerContext(WorkerContextRegistration request) {
        requireStartedEngine();
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.WORKER_CONTEXT_REGISTER)
                .payload(Map.of("request", request))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        requireSuccessfulEventResponse(response);
    }

    @Override
    public String getWorkerAdapterId(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        Worker worker = getWorker(normalizedWorkerId);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + normalizedWorkerId);
        }
        if (delegate.getTransportRuntimeRegistry() != null) {
            return delegate.getTransportRuntimeRegistry().resolveWorkerAdapterId(normalizedWorkerId);
        }
        if (worker.getAdapterId() == null || worker.getAdapterId().isBlank()) {
            throw new IllegalStateException("Worker adapterId is not set: " + worker.getWorkerId());
        }
        return worker.getAdapterId().trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String getWorkerTransportHint(String workerId) {
        Worker worker = getWorker(requireWorkerId(workerId));
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + requireWorkerId(workerId));
        }
        String transportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (transportHint == null && delegate.getTransportRuntimeRegistry() != null) {
            transportHint = delegate.getTransportRuntimeRegistry().resolveWorkerTransportHint(worker.getWorkerId());
        }
        if (transportHint == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy is not set: " + worker.getWorkerId());
        }
        return transportHint;
    }

    public Worker getWorker(String workerId) {
        return requireStartedWorkerStorage().getWorker(workerId).orElse(null);
    }

    public List<Worker> getAllWorkers() {
        return requireStartedWorkerStorage().getAllWorkers();
    }

    public List<WorkerContext> getAllWorkerContexts() {
        return requireStartedWorkerStorage().getAllWorkerContexts();
    }

    public List<WorkerContext> getWorkerContexts(String workerId) {
        return requireStartedWorkerStorage().getWorkerContexts(workerId);
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
        return pollTasks(workerId, maxMessages, 0L);
    }

    @Override
    public List<TaskDispatchItem> pollTasks(String workerId, int maxMessages, long timeoutMillis) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than 0");
        }
        return externalPullWorkerSession(workerId).poll(maxMessages, timeoutMillis);
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
        return requireStartedWorkerStorage().getWorkerContextById(workerContextId).orElse(null);
    }

    public boolean isWorkerLocked(String workerId) {
        return requireStartedWorkerStorage().isLocked(workerId);
    }

    public boolean isWorkerOnline(String workerId) {
        Worker worker = getWorker(requireWorkerId(workerId));
        return worker != null && worker.getStatus() == WorkerStatus.ONLINE;
    }

    @Override
    public boolean updateWorkerSupportedProjects(String workerId, List<String> supportedProjects) {
        WorkerStorage workerStorage = requireStartedWorkerStorage();
        Worker worker = workerStorage.getWorker(requireWorkerId(workerId)).orElse(null);
        if (worker == null) {
            return false;
        }
        worker.setSupportedProjects(normalizedProjectCodes(supportedProjects));
        return workerStorage.updateWorker(worker);
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
    public PrincipalContext authenticateSubmitter(String credential) {
        return submitterRegistry.authenticate(credential);
    }

    @Override
    public PrincipalContext getPrincipal(String principalId) {
        return submitterRegistry.getPrincipal(principalId);
    }

    @Override
    public PrincipalContext authenticate(String credential) {
        return authenticateSubmitter(credential);
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        return authorizationPolicy.authorize(request);
    }

    private void registerControlPlaneEventHandlers() {
        registerPlatformEvent(
                PlatformEventCodes.WORKER_REGISTER,
                "Platform Worker Register",
                "Register a worker identity and capability record.",
                (request, principal) -> {
                    WorkerRegistration registration = resolveWorkerRegistration(request);
                    requireStartedWorkerStorage().addWorker(SdkResourceMapper.toWorker(registration));
                    return CoreEventResponse.success(Boolean.TRUE, request.getRequestId());
                }
        );
        registerPlatformEvent(
                PlatformEventCodes.WORKER_CONTEXT_REGISTER,
                "Platform Worker Context Register",
                "Register a worker execution context.",
                (request, principal) -> {
                    WorkerContextRegistration registration = resolveWorkerContextRegistration(request);
                    requireStartedWorkerStorage().addWorkerContext(SdkResourceMapper.toWorkerContext(registration));
                    return CoreEventResponse.success(Boolean.TRUE, request.getRequestId());
                }
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_APPROVE,
                "Platform Task Approve",
                "Approve a task and move it into scheduling.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().approveTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_REJECT,
                "Platform Task Reject",
                "Reject a task and block it before scheduling.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().rejectTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_BLOCK,
                "Platform Task Block",
                "Block an active or ready task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().blockTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_PAUSE,
                "Platform Task Pause",
                "Pause a running or ready task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().pauseTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_RESUME,
                "Platform Task Resume",
                "Resume a paused task.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().resumeTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_CANCEL,
                "Platform Task Cancel",
                "Cancel a task and close it to terminal.",
                (request, principal) -> CoreEventResponse.success(
                        requireStartedTaskCommands().cancelTask(readRequiredString(request.getPayload(), "taskId")),
                        request.getRequestId())
        );
        registerPlatformEvent(
                PlatformEventCodes.TASK_TERMINATE,
                "Platform Task Terminate",
                "Terminate a task with an explicit terminal reason.",
                (request, principal) -> CoreEventResponse.success(
                                requireStartedTaskCommands().terminateTask(
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
                        requireStartedTaskCommands().appendTaskItems(
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
                        requireStartedTaskCommands().sealTask(readRequiredString(request.getPayload(), "taskId")),
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

    private EventResponse dispatchEventInternal(EventRequest request, PrincipalContext principal) {
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

    private PrincipalContext toSdkPrincipal(CoreEventPrincipal principal) {
        if (principal == null) {
            return PrincipalContext.builder()
                    .principalId("anonymous")
                    .principalType(PrincipalType.SERVICE)
                    .build();
        }
        return PrincipalContext.builder()
                .principalId(principal.clientId() == null ? "anonymous" : principal.clientId())
                .principalType(PrincipalType.SERVICE)
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
                                                   PrincipalContext principal,
                                                   EventDefinition definition) {
        MassTaskRequest taskRequest = TaskOwnershipSupport.stamp(
                resolveTaskRequest(request, principal, definition),
                principal == null ? internalPrincipal(null) : principal
        );
        validateTaskCatalogContract(taskRequest);
        Task task = requireStartedEngine().createTask(MassTaskRequestMapper.toEngineRequest(taskRequest));
        return EventResponse.success(task, request.getRequestId());
    }

    private MassTaskRequest resolveTaskRequest(EventRequest request,
                                               PrincipalContext principal,
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
                .adapterId(readString(payload, "adapterId", null))
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

    private CoreEventPrincipal toCorePrincipal(PrincipalContext principal) {
        return new CoreEventPrincipal(
                principal == null ? null : principal.getPrincipalId(),
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

    private PrincipalContext internalPrincipal(String userId) {
        return PrincipalContext.internalService("sdk-internal", userId);
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

    private List<String> normalizedStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim());
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private List<String> normalizedProjectCodes(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(ProjectRegistry.require(value.trim()).getCode());
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
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

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId.trim();
    }

    private String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return messageId.trim();
    }

    private PullWorkerSession externalPullWorkerSession(String workerId) {
        return pullWorker(requireWorkerId(workerId));
    }

    private WorkerRegistration normalizeWorkerRegistration(WorkerRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        List<WorkerEventBinding> bindings = normalizedWorkerBindings(registration);
        LinkedHashSet<String> supportedEventCodes = new LinkedHashSet<>();
        LinkedHashSet<String> supportedProjects = new LinkedHashSet<>();
        if (bindings != null && !bindings.isEmpty()) {
            for (WorkerEventBinding binding : bindings) {
                EventDefinition definition = requireEnabledEventDefinition(binding.getEventCode());
                supportedEventCodes.add(definition.getCode());
                supportedProjects.addAll(resolveWorkerBindingProjects(definition, binding));
            }
        } else {
            supportedEventCodes.addAll(normalizedStringList(registration.getSupportedEventCodes()));
            supportedProjects.addAll(normalizedStringList(registration.getSupportedProjects()));
        }
        String normalizedTransportHint =
                WorkerTransportHints.normalize(requireNonBlank(registration.getTransportHint(), "transportHint"));
        String resolvedAdapterId = resolveRegistrationAdapterId(registration.getAdapterId(), normalizedTransportHint);

        return WorkerRegistration.builder()
                .workerId(registration.getWorkerId())
                .workerGroupId(registration.getWorkerGroupId())
                .supportedProjects(List.copyOf(supportedProjects))
                .supportedEventCodes(List.copyOf(supportedEventCodes))
                .eventBindings(bindings)
                .adapterId(resolvedAdapterId)
                .transportHint(normalizedTransportHint)
                .attributes(registration.getAttributes())
                .build();
    }

    private List<WorkerEventBinding> normalizedWorkerBindings(WorkerRegistration registration) {
        List<WorkerEventBinding> explicitBindings = registration.getEventBindings();
        if (explicitBindings != null && !explicitBindings.isEmpty()) {
            return List.copyOf(explicitBindings);
        }
        List<String> legacyEventCodes = normalizedStringList(registration.getSupportedEventCodes());
        if (legacyEventCodes.isEmpty()) {
            return List.of();
        }
        List<String> legacyProjectCodes = normalizedStringList(registration.getSupportedProjects());
        List<WorkerEventBinding> derivedBindings = new ArrayList<>(legacyEventCodes.size());
        for (String eventCode : legacyEventCodes) {
            derivedBindings.add(WorkerEventBinding.builder()
                    .eventCode(eventCode)
                    .projectCodes(legacyProjectCodes)
                    .build());
        }
        return List.copyOf(derivedBindings);
    }

    private String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        if (delegate.getTransportRuntimeRegistry() != null) {
            return delegate.getTransportRuntimeRegistry().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
        }
        String delegateResolvedAdapterId = delegate.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
        if (delegateResolvedAdapterId != null && !delegateResolvedAdapterId.isBlank()) {
            return delegateResolvedAdapterId;
        }
        throw new IllegalStateException(
                "Worker adapterId could not be resolved because transport runtime registration metadata is unavailable");
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
        return requireStartedRuleStorage().getAllRules().stream()
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
        return requireStartedRuleStorage().getRegisteredEvaluatorTypes().stream().map(Enum::name).toList();
    }

    @Override
    public void replaceDefaultRules(Collection<RuleDefinition> rules) {
        Objects.requireNonNull(rules, "rules");
        RuleStorage ruleStorage = requireStartedRuleStorage();
        ruleStorage.clear();
        ruleStorage.addRules(List.copyOf(rules));
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
                connectionInfo.put("routeKey", snapshot.getRouteKey());
                connectionInfo.put("adapterId", snapshot.getAdapterId());
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
            if (endpointInspector != null) {
                List<WorkerEndpointSnapshot> snapshots = endpointInspector.listWorkerEndpoints();
                data.put("workerCount", snapshots.stream().map(WorkerEndpointSnapshot::getWorkerId).distinct().count());
                Map<String, Long> activeConnectionsByAdapter = new LinkedHashMap<>();
                snapshots.stream()
                        .filter(WorkerEndpointSnapshot::isActive)
                        .forEach(snapshot -> activeConnectionsByAdapter.merge(
                                snapshot.getAdapterId(),
                                1L,
                                Long::sum
                        ));
                data.put("activeConnectionsByAdapter", activeConnectionsByAdapter);
            } else {
                data.put("workerCount", 0L);
                data.put("activeConnectionsByAdapter", Map.of());
            }
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0L);
            data.put("activeConnectionsByAdapter", Map.of());
        }
        return data;
    }

    @Override
    public Map<String, Object> enqueueRawMessage(Map<String, Object> request) {
        Object workerId = request.get("workerId");
        if (!(workerId instanceof String workerIdText) || workerIdText.isBlank()) {
            return Map.of("success", false, "msg", "workerId is required");
        }
        Object rawJson = request.get("rawJson");
        String payload = rawJson instanceof String rawText ? rawText : GSON.toJson(request);
        boolean accepted = delegate.sendRawTransportMessage(
                workerIdText.trim(),
                payload,
                UUID.randomUUID().toString()
        );
        if (!accepted) {
            return Map.of("success", false, "msg", "no transport side-channel accepted a unique active worker route");
        }
        return Map.of("success", true, "msg", "message enqueued");
    }

    @Override
    public Map<String, Object> getQueueDetail() {
        return delegate.getTransportQueueDetail();
    }

    @Override
    public Map<String, Object> getQueueMetrics() {
        return Map.of(
                "inputQueueRate", 0,
                "outputQueueRate", 0
        );
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

    /**
     * Registers a listener that fires synchronously when a task message reaches its
     * logically final state (success or exhausted retries). Safe to call before
     * {@link #start()} 鈥?the listener is registered on the engine command/event
     * surface which exists independent of engine lifecycle.
     */
    public void addTaskMessageLogicallyFinalListener(TaskMessageLogicallyFinalListener listener) {
        Objects.requireNonNull(listener, "listener");
        requireStartedTaskEvents().addTaskMessageLogicallyFinalListener(listener);
    }

    private TaskCommandService requireStartedTaskCommands() {
        TaskCommandService taskCommands = requireStartedEngine().getConfig().getTaskCommandService();
        if (taskCommands == null) {
            throw new IllegalStateException("Task command service is unavailable for this SDK application");
        }
        return taskCommands;
    }

    private TaskQueryService requireStartedTaskQueries() {
        TaskQueryService taskQueries = requireStartedEngine().getConfig().getTaskQueryService();
        if (taskQueries == null) {
            throw new IllegalStateException("Task query service is unavailable for this SDK application");
        }
        return taskQueries;
    }

    private TaskEventService requireStartedTaskEvents() {
        TaskEventService taskEvents = requireStartedEngine().getConfig().getTaskEventService();
        if (taskEvents == null) {
            throw new IllegalStateException("Task event service is unavailable for this SDK application");
        }
        return taskEvents;
    }

    private TaskDetailStore requireStartedTaskDetailStore() {
        TaskDetailStore taskDetailStore = requireStartedEngine().getConfig().getTaskDetailStore();
        if (taskDetailStore == null) {
            throw new IllegalStateException("Task detail store is unavailable for this SDK application");
        }
        return taskDetailStore;
    }

    private WorkerStorage requireStartedWorkerStorage() {
        WorkerStorage workerStorage = requireStartedEngine().getConfig().getWorkerStorage();
        if (workerStorage == null) {
            throw new IllegalStateException("Worker storage is unavailable for this SDK application");
        }
        return workerStorage;
    }

    private RuleStorage requireStartedRuleStorage() {
        RuleStorage ruleStorage = requireStartedEngine().getConfig().getRuleStorage();
        if (ruleStorage == null) {
            throw new IllegalStateException("Rule storage is unavailable for this SDK application");
        }
        return ruleStorage;
    }

    private WorkerEndpointRegistry resolveEndpointRegistry() {
        return delegate.getEndpointRegistry();
    }

    private WorkerEndpointInspector resolveEndpointInspector() {
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        return endpointRegistry instanceof WorkerEndpointInspector inspector ? inspector : null;
    }

    private SdkTaskMessageView toSdkTaskMessageView(TaskMsg taskMsg) {
        return new SdkTaskMessageView(
                taskMsg.getMessageId(),
                taskMsg.getTaskId(),
                enumName(taskMsg.getStatus()),
                taskMsg.latestAttemptId(),
                taskMsg.getLatestAttemptWorkerId(),
                taskMsg.getLatestAttemptWorkerContextId(),
                taskMsg.getLatestAttemptBatchId(),
                taskMsg.getRetryCount(),
                taskMsg.getMaxRetryCount(),
                taskMsg.getErrorMessage(),
                taskMsg.getErrorCode(),
                enumName(taskMsg.getFinalReason()),
                taskMsg.getPayloadRef(),
                copyMap(taskMsg.getInput()),
                copyMap(taskMsg.getOutput()),
                taskMsg.getAssignedTime(),
                taskMsg.getCreateTime(),
                taskMsg.getUpdateTime(),
                taskMsg.getStartTime(),
                taskMsg.getCompleteTime()
        );
    }

    private SdkTaskMessageAttemptView toSdkTaskMessageAttemptView(TaskMsgAttempt attempt) {
        return new SdkTaskMessageAttemptView(
                attempt.getAttemptId(),
                attempt.getTaskId(),
                attempt.getMessageId(),
                attempt.getAttemptNo(),
                attempt.getWorkerId(),
                attempt.getWorkerContextId(),
                attempt.getBatchId(),
                enumName(attempt.getStatus()),
                attempt.getLeaseExpireTime(),
                attempt.getDispatchTime(),
                attempt.getAckTime(),
                attempt.getStartTime(),
                attempt.getFinishTime(),
                enumName(attempt.getFinalReason()),
                attempt.getErrorMessage(),
                attempt.getErrorCode(),
                copyMap(attempt.getOutput()),
                attempt.getCreateTime(),
                attempt.getUpdateTime()
        );
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
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
        MassEngine engine = delegate.getEngine();
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this SDK application");
        }
        if (!engine.isRunning()) {
            throw new IllegalStateException("Mass engine has not been started");
        }
        return engine;
    }
}
