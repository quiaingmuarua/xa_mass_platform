package com.xa.mass.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
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
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.CodecContext;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.dispatcher.handler.WorkerControlEventBridgeHandler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.sdk.auth.*;
import com.xa.mass.sdk.authz.*;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.PlatformEventCodes;
import com.xa.mass.sdk.model.*;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointRoles;
import com.xa.mass.transport.WorkerEndpointSnapshot;

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
        RuleOperations, TransportOperations, DebugOperations {

    private static final Gson GSON = new Gson();

    private final MassApplication delegate;
    private final ProjectEventCatalogRegistry projectEventCatalogRegistry;
    private final InMemorySubmitterRegistry submitterRegistry;
    private final InMemoryClientPermissionProvider clientPermissionProvider;
    private final InMemoryUserPermissionProvider userPermissionProvider;
    private final EventPermissionService eventPermissionService;
    private final MassEventRuntime eventRuntime;

    MassSdkApplication(MassApplication delegate) {
        this(delegate, DefaultProjectEventCatalogFactory.createDefaultRegistry());
    }

    MassSdkApplication(MassApplication delegate, ProjectEventCatalogRegistry projectEventCatalogRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.projectEventCatalogRegistry = Objects.requireNonNull(projectEventCatalogRegistry, "projectEventCatalogRegistry");
        this.submitterRegistry = new InMemorySubmitterRegistry();
        this.clientPermissionProvider = new InMemoryClientPermissionProvider();
        this.userPermissionProvider = new InMemoryUserPermissionProvider();
        this.eventRuntime = delegate.getEventRuntime() != null ? delegate.getEventRuntime() : new InMemoryMassEventRuntime();
        this.eventPermissionService = new DefaultEventPermissionService(
                clientPermissionProvider,
                userPermissionProvider,
                projectEventCatalogRegistry,
                eventRuntime
        );
        registerEnabledCatalogProjectsIntoCore();
        registerControlPlaneEventHandlers();
        delegate.setSdkEventDispatcher(this::dispatchEvent);
        delegate.setWorkerControlEventBridgeHandler(
                new WorkerControlEventBridgeHandler(new EventGatewayBridge(this::dispatchEvent))
        );
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
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
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
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
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
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
    }

    @Override
    public void registerWorkerContext(WorkerContextRegistration request) {
        EventResponse response = dispatchEventInternal(EventRequest.builder()
                .event(PlatformEventCodes.WORKER_CONTEXT_REGISTER)
                .payload(Map.of("request", request))
                .requestId(UUID.randomUUID().toString())
                .build(), internalPrincipal(null));
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
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
        if (projectEventCatalogRegistry.getEvent(eventCode) == null) {
            projectEventCatalogRegistry.registerEvent(EventMetadata.builder()
                    .code(eventCode)
                    .name(name)
                    .description(description)
                    .payloadTypes(List.of(PayloadType.JSON))
                    .taskModes(List.of())
                    .build());
        }
        eventRuntime.register(
                CoreEventDescriptor.builder()
                        .event(eventCode)
                        .summary(description)
                        .build(),
                handler
        );
    }

    private EventResponse dispatchEventInternal(EventRequest request, EventPrincipal principal) {
        try {
            String eventCode = request.getEvent().value();
            if (eventRuntime.contains(eventCode)) {
                return toSdkResponse(eventRuntime.dispatch(toCoreRequest(request), toCorePrincipal(principal)));
            }
            EventMetadata eventMetadata = projectEventCatalogRegistry.getEvent(eventCode);
            if (eventMetadata != null) {
                return dispatchCatalogTaskEvent(request, principal, eventMetadata);
            }
            return EventResponse.failure("UNKNOWN_EVENT", "unknown event: " + eventCode, request.getRequestId());
        } catch (IllegalArgumentException e) {
            return EventResponse.failure("BAD_REQUEST", e.getMessage(), request.getRequestId());
        } catch (IllegalStateException e) {
            return EventResponse.failure("ILLEGAL_STATE", e.getMessage(), request.getRequestId());
        } catch (Exception e) {
            return EventResponse.failure("ERROR", e.getMessage(), request.getRequestId());
        }
    }

    private EventResponse dispatchCatalogTaskEvent(EventRequest request,
                                                   EventPrincipal principal,
                                                   EventMetadata eventMetadata) {
        MassTaskRequest taskRequest = resolveTaskRequest(request, principal, eventMetadata);
        validateTaskCatalogContract(taskRequest);
        Task task = requireStartedEngine().createTask(MassTaskRequestMapper.toEngineRequest(taskRequest));
        return EventResponse.success(task, request.getRequestId());
    }

    private MassTaskRequest resolveTaskRequest(EventRequest request,
                                               EventPrincipal principal,
                                               EventMetadata eventMetadata) {
        Object embeddedRequest = request.getPayload().get("request");
        if (embeddedRequest instanceof MassTaskRequest massTaskRequest) {
            return massTaskRequest;
        }

        Map<String, Object> payload = request.getPayload();
        Map<String, String> headers = request.getHeaders();
        TaskMode mode = parseTaskMode(headers.get("taskMode"), eventMetadata);
        PayloadType payloadType = parsePayloadType(headers.get("payloadType"), eventMetadata);
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
            return registration;
        }
        Map<String, Object> payload = request.getPayload();
        return WorkerRegistration.builder()
                .workerId(readRequiredString(payload, "workerId"))
                .workerGroupId(readString(payload, "workerGroupId", null))
                .supportedProjects(readStringList(payload.get("supportedProjects")))
                .supportedEventCodes(readStringList(payload.get("supportedEventCodes")))
                .transportHint(readString(payload, "transportHint", null))
                .attributes(readStringMap(payload.get("attributes")))
                .build();
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
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getMessage());
        }
        return Boolean.TRUE.equals(response.getData());
    }

    private EventPrincipal internalPrincipal(String userId) {
        return EventPrincipal.builder()
                .clientId("sdk-internal")
                .userId(userId)
                .build();
    }

    private TaskMode parseTaskMode(String rawValue, EventMetadata eventMetadata) {
        if (rawValue != null && !rawValue.isBlank()) {
            return TaskMode.valueOf(rawValue.trim().toUpperCase());
        }
        if (eventMetadata != null && !eventMetadata.getTaskModes().isEmpty()) {
            return eventMetadata.getTaskModes().get(0);
        }
        return TaskMode.SINGLE_RUN;
    }

    private PayloadType parsePayloadType(String rawValue, EventMetadata eventMetadata) {
        if (rawValue != null && !rawValue.isBlank()) {
            return PayloadType.valueOf(rawValue.trim().toUpperCase());
        }
        if (eventMetadata != null && !eventMetadata.getPayloadTypes().isEmpty()) {
            return eventMetadata.getPayloadTypes().get(0);
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
    public Map<String, Object> sendWorkerEvent(String workerId,
                                               EventRequest request,
                                               EventPrincipal principal) {
        Objects.requireNonNull(request, "request");
        String eventRequestId = firstNonBlank(request.getRequestId(), UUID.randomUUID().toString());
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put(WorkerControlEventProtocol.EVENT_FIELD, request.getEvent().value());
        eventPayload.put(WorkerControlEventProtocol.REQUEST_ID_FIELD, eventRequestId);
        eventPayload.put(WorkerControlEventProtocol.HEADERS_FIELD, request.getHeaders());
        eventPayload.put(WorkerControlEventProtocol.PAYLOAD_FIELD, request.getPayload());
        Map<String, Object> principalPayload = new LinkedHashMap<>();
        if (principal != null) {
            principalPayload.put(WorkerControlEventProtocol.CLIENT_ID_FIELD, principal.getClientId());
            principalPayload.put(WorkerControlEventProtocol.USER_ID_FIELD, principal.getUserId());
        }
        eventPayload.put(WorkerControlEventProtocol.PRINCIPAL_FIELD, principalPayload);
        Map<String, Object> dispatchResult = enqueueWorkerControlEvent(
                workerId,
                firstNonBlank(request.getProject(), null),
                eventPayload
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", dispatchResult.get("messageId"));
        result.put("workerId", dispatchResult.get("workerId"));
        result.put("project", dispatchResult.get("project"));
        result.put(WorkerControlEventProtocol.EVENT_FIELD, request.getEvent().value());
        result.put(WorkerControlEventProtocol.REQUEST_ID_FIELD, eventRequestId);
        return result;
    }

    private Map<String, Object> enqueueWorkerControlEvent(String workerId,
                                                          String project,
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
        JsonElement payloadJson = toPayloadJson(payload);
        String messageId = UUID.randomUUID().toString();
        String resolvedSubMsgType = WorkerControlEventProtocol.SUB_MSG_TYPE;

        MassMessage message = new MassMessage();
        message.setMsgId(messageId);
        message.setMsgType(MessageType.CONTROL);
        message.setSubMsgType(resolvedSubMsgType);
        message.setFrom(MessageDirection.SERVER);
        message.setProject(resolvedProject);
        message.setContext(buildMessageContext(workerId));
        message.setPayload(payloadJson);

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
                MessageType.CONTROL.name(),
                resolvedSubMsgType,
                messageId,
                GSON.toJson(payloadJson),
                rawJson,
                "message queued to dispatcher"
        );
        transportContext.getMessageTransporter().sendOutput(envelope);
        return Map.of(
                "messageId", messageId,
                "workerId", workerId,
                "project", resolvedProject,
                "msgType", MessageType.CONTROL.name(),
                "subMsgType", resolvedSubMsgType
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
