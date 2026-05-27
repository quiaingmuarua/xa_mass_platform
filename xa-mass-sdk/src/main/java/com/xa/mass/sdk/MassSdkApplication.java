package com.xa.mass.sdk;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.command.event.*;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.engine.command.WorkerCommandStatus;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.stage.TaskStageEvidenceResult;
import com.xa.mass.engine.stage.TaskStageEvidenceService;
import com.xa.mass.engine.stage.TaskStageProjection;
import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.engine.worker.WorkerControlService;
import com.xa.mass.runtime.worker.WorkerGroupRecord;
import com.xa.mass.runtime.worker.AdapterNodeRecord;
import com.xa.mass.runtime.worker.NodeGroupBindingRecord;
import com.xa.mass.runtime.worker.WorkerCapabilityReport;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import com.xa.mass.runtime.worker.WorkerStateProjection;
import com.xa.mass.runtime.worker.WorkerStateProjectionResult;
import com.xa.mass.runtime.worker.WorkerStateReport;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;
import com.xa.mass.storage.api.RuleStorage;
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
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Consumer-facing runtime handle returned by the SDK facade.
 *
 * <p>The SDK artifact also carries the lower-level {@link MassApplication}
 * runtime, but the stable embedding path stays on {@code com.xa.mass.sdk.*}
 * methods rather than exposing starter/runtime internals directly.
 */
public final class MassSdkApplication implements MassRuntimeControl, TaskQueryOperations, TaskResultQueryOperations, TaskAdminOperations,
        WorkerInspectionOperations, WorkerQueryOperations, WorkerRegistryOperations,
        WorkerTopologyOperations,
        WorkerClientOperations, WorkerAdminOperations,
        WorkerControlOperations, TaskStageEvidenceOperations,
        ResourceOperations, AuthProvider, PrincipalDirectory,
        ExternalWorkerOperations, AuthorizationPolicy,
        RuleOperations {

    private static final int ARCHIVE_STREAM_WINDOW = Integer.getInteger("xa.mass.sdk.resultArchiveStreamWindow", 1000);
    private static final String RESULT_ARCHIVE_FORMAT = "ndjson";
    private static final String RESULT_ARCHIVE_CONTENT_TYPE = "application/x-ndjson";
    private static final String RESULT_ARCHIVE_CONTENT_ENCODING = "gzip";
    private static final com.google.gson.Gson RESULT_JSON = new com.google.gson.Gson();

    private final MassApplication delegate;
    private final ProjectEventCatalogRegistry bootstrapProjectCatalogRegistry;
    private final SubmitterRegistry submitterRegistry;
    private final EventPermissionService eventPermissionService;
    private final AuthorizationPolicy authorizationPolicy;
    private final MassEventRuntime eventRuntime;
    private final EventDefinitionRegistry eventDefinitionRegistry;
    private final Map<String, EventHandler> eventHandlerCache;
    private final ControlPlaneCatalog controlPlaneCatalogView;
    private final TaskDiagnosticOperations taskDiagnostics;
    private final RuntimeDiagnosticsOperations runtimeDiagnostics;

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
        this.controlPlaneCatalogView = new DefinitionBackedControlPlaneCatalog(
                this::listProjects,
                this::getProject,
                this::listEvents,
                this::getEvent,
                this::getEventsForProject
        );
        this.taskDiagnostics = new DefaultTaskDiagnosticOperations(this::requireStartedTaskQueries);
        this.runtimeDiagnostics = new DefaultRuntimeDiagnosticsOperations(delegate);
        this.eventPermissionService = new DefaultEventPermissionService(controlPlaneCatalogView);
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

    /**
     * Advanced embedded-runtime seam for operator shells and server wiring that
     * intentionally live below the stable SDK mainline surface.
     */
    MassApplication runtimeApplication() {
        return delegate;
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
    public TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request) {
        MassEngine engine = requireStartedEngine();
        MassTaskShellCreateRequest stamped = TaskOwnershipSupport.stamp(request, internalPrincipal(request.getUserId()));
        WorkerGroupSelectorResolver.requireExplicitTargetWorkerBinding(stamped.getSharedConfig());
        return toTaskShellSnapshot(engine.createTaskShell(SdkResourceMapper.toEngineRequest(stamped)));
    }

    @Override
    public TaskDetailSnapshot getTaskDetail(String taskId) {
        return toTaskDetailSnapshot(requireStartedTaskQueries().getTask(taskId));
    }

    @Override
    public List<TaskSummarySnapshot> listTaskSummaries(int offset, int limit) {
        return requireStartedTaskQueries().listTasksPaged(offset, limit).stream()
                .map(this::toTaskSummarySnapshot)
                .toList();
    }

    @Override
    public List<TaskSummarySnapshot> getTaskSummariesByStatus(String status) {
        return requireStartedTaskQueries().getTasksByStatus(
                parseTaskStatus(status)
        ).stream().map(this::toTaskSummarySnapshot).toList();
    }

    @Override
    public boolean taskExists(String taskId) {
        return requireStartedTaskQueries().getTask(requireTaskId(taskId)) != null;
    }

    @Override
    public TaskStateSnapshot getTaskState(String taskId) {
        Task task = requireStartedTaskQueries().getTask(requireTaskId(taskId));
        if (task == null) {
            return null;
        }
        return toTaskStateSnapshot(task);
    }

    @Override
    public TaskAccessSnapshot getTaskAccess(String taskId) {
        Task task = requireStartedTaskQueries().getTask(requireTaskId(taskId));
        if (task == null) {
            return null;
        }
        return toTaskAccessSnapshot(task);
    }

    @Override
    public TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit) {
        TaskResultWindow window = requireStartedTaskResultRuntime()
                .readWindow(requireTaskId(taskId), Math.max(0L, afterSeq), Math.max(1, limit));
        return toTaskResultWindowSnapshot(window);
    }

    @Override
    public Optional<TaskWorkFinalSnapshot> getTaskWorkFinal(String taskId, String messageId) {
        return requireStartedTaskResultRuntime()
                .getVisibleByMessageId(requireTaskId(taskId), requireMessageId(messageId))
                .map(this::toTaskWorkFinalSnapshot);
    }

    @Override
    public TaskResultArchiveSnapshot getTaskResultArchiveManifest(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        TaskDetailSnapshot task = getTaskDetail(normalizedTaskId);
        boolean ready = task != null && "TERMINAL".equalsIgnoreCase(task.getStatus());
        long itemCount = requireStartedTaskResultRuntime().countVisibleResults(normalizedTaskId);
        return new TaskResultArchiveSnapshot(
                normalizedTaskId,
                ready,
                RESULT_ARCHIVE_FORMAT,
                RESULT_ARCHIVE_CONTENT_TYPE,
                RESULT_ARCHIVE_CONTENT_ENCODING,
                ready ? itemCount : 0L,
                null,
                null
        );
    }

    @Override
    public void writeTaskResultArchiveContent(String taskId, OutputStream sink) {
        Objects.requireNonNull(sink, "sink");
        String normalizedTaskId = requireTaskId(taskId);
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(sink);
            long afterSeq = 0L;
            while (true) {
                TaskResultWindow window = requireStartedTaskResultRuntime()
                        .readWindow(normalizedTaskId, afterSeq, ARCHIVE_STREAM_WINDOW);
                for (TaskResultRuntimeRow row : window.items()) {
                    gzip.write(RESULT_JSON.toJson(toTaskResultItemSnapshot(row)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    gzip.write('\n');
                }
                if (!window.hasMore()) {
                    gzip.finish();
                    gzip.flush();
                    return;
                }
                afterSeq = window.nextAfterSeq();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream task result archive: " + e.getMessage(), e);
        }
    }

    public boolean approveTask(String taskId) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("APPROVE").build()).isAccepted();
    }

    public boolean rejectTask(String taskId) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("REJECT").build()).isAccepted();
    }

    public boolean blockTask(String taskId) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("BLOCK").build()).isAccepted();
    }

    public boolean pauseTask(String taskId) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("PAUSE").build()).isAccepted();
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
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("RESUME").build()).isAccepted();
    }

    public boolean cancelTask(String taskId) {
        return requireStartedTaskCommands().cancelTask(requireTaskId(taskId));
    }

    public boolean terminateTask(String taskId, String reason) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder()
                .command("TERMINATE")
                .reason(reason)
                .build()).isAccepted();
    }

    @Override
    public TaskItemBatchAppendReceipt appendTaskItemsWithReceipt(String taskId, MassTaskItemBatchAppendRequest request) {
        Objects.requireNonNull(request, "request");
        String normalizedTaskId = requireTaskId(taskId);
        resolveWorkerGroupSelectorForAppend(normalizedTaskId, request.getEventCode());
        List<Map<String, Object>> converted = requireAppendItems(request.getItems(), request.getEventCode());
        com.xa.mass.engine.model.TaskAppendReceipt receipt =
                requireStartedTaskCommands().appendTaskItemsWithReceipt(normalizedTaskId, converted);
        return new TaskItemBatchAppendReceipt(receipt.taskId(), receipt.added(), receipt.messageIds());
    }

    @Override
    public int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request) {
        return appendTaskItemsWithReceipt(taskId, request).added();
    }

    public boolean sealTask(String taskId) {
        return executeTaskCommand(taskId, MassTaskCommandRequest.builder().command("SEAL").build()).isAccepted();
    }

    public TaskDiagnosticOperations taskDiagnostics() {
        return taskDiagnostics;
    }

    public RuntimeDiagnosticsOperations runtimeDiagnostics() {
        return runtimeDiagnostics;
    }

    @Override
    public boolean updateTaskDefinition(String taskId, MassTaskUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        Task task = requireStartedTaskQueries().getTask(requireTaskId(taskId));
        if (task == null) {
            return false;
        }
        if (request.getProject() != null) {
            task.setProject(request.getProject());
        }
        if (request.getSharedConfig() != null) {
            WorkerGroupSelectorResolver.requireExplicitTargetWorkerBinding(request.getSharedConfig());
            task.setSharedConfig(request.getSharedConfig());
        }
        if (request.getUserId() != null) {
            task.setUser(UserRef.of(request.getUserId()));
        }
        return requireStartedTaskCommands().updateTask(task);
    }

    @Override
    public TaskCommandResult executeTaskCommand(String taskId, MassTaskCommandRequest request) {
        Objects.requireNonNull(request, "request");
        String normalizedTaskId = requireTaskId(taskId);
        String normalizedCommand = normalizeTaskCommand(request.getCommand());
        TaskQueryService taskQueries = requireStartedTaskQueries();
        TaskCommandService taskCommands = requireStartedTaskCommands();

        Task before = taskQueries.getTask(normalizedTaskId);
        if (before == null) {
            return toTaskCommandResult(normalizedTaskId, normalizedCommand, false, false,
                    null, "Task not found", "TASK_NOT_FOUND");
        }

        boolean accepted = switch (normalizedCommand) {
            case "APPROVE" -> taskCommands.approveTask(normalizedTaskId);
            case "REJECT" -> taskCommands.rejectTask(normalizedTaskId);
            case "BLOCK" -> executeBlockTask(taskCommands, normalizedTaskId, before);
            case "PAUSE" -> taskCommands.pauseTask(normalizedTaskId);
            case "RESUME" -> taskCommands.resumeTaskDetailed(normalizedTaskId).isSuccess();
            case "TERMINATE" -> taskCommands.terminateTask(
                    normalizedTaskId,
                    parseTaskTerminalReason(request.getReason(),
                            com.xa.mass.base.enums.task.TaskTerminalReason.MANUAL_CANCELLED)
            );
            case "SEAL" -> taskCommands.sealTask(normalizedTaskId);
            default -> throw new IllegalArgumentException("Unsupported task command: " + normalizedCommand);
        };

        Task after = taskQueries.getTask(normalizedTaskId);
        if (accepted) {
            return toTaskCommandResult(normalizedTaskId, normalizedCommand, true, true,
                    after != null ? after : before, null, null);
        }
        return toTaskCommandResult(normalizedTaskId, normalizedCommand, false, true,
                after != null ? after : before,
                "Task command is not allowed in the current state",
                "COMMAND_NOT_ALLOWED");
    }

    @Override
    public void registerAdapterNode(AdapterNodeRegistration request) {
        MassEngine engine = requireStartedEngine();
        Objects.requireNonNull(request, "request");
        engine.getConfig().getWorkerResourceRuntime().registerAdapterNode(new AdapterNodeRecord(
                request.getAdapterNodeId(),
                request.getAdapterType(),
                request.getAdapterVersion(),
                request.getEndpointId(),
                request.isEnabled(),
                request.isOnline(),
                null,
                null,
                request.getAttributes()
        ));
    }

    @Override
    public void bindNodeGroup(NodeGroupBindingRegistration request) {
        MassEngine engine = requireStartedEngine();
        Objects.requireNonNull(request, "request");
        engine.getConfig().getWorkerResourceRuntime().bindNodeGroup(new NodeGroupBindingRecord(
                request.getAdapterNodeId(),
                request.getWorkerGroupId(),
                request.getPluginVersion(),
                request.getDeploymentVersion(),
                request.isEnabled(),
                request.isDraining(),
                null,
                null,
                request.getAttributes()
        ));
    }

    @Override
    public void declareWorkerGroup(WorkerGroupDeclaration request) {
        MassEngine engine = requireStartedEngine();
        engine.getConfig().getWorkerResourceRuntime().upsertWorkerGroup(toWorkerGroupRecord(
                Objects.requireNonNull(request, "request")
        ));
    }

    @Override
    public void registerWorker(WorkerRegistration request) {
        requireStartedEngine();
        WorkerRegistration registration = normalizeWorkerRegistration(request);
        requireStartedEngine().getConfig().getWorkerResourceRuntime()
                .addWorker(SdkResourceMapper.toWorkerResourceRecord(registration));
    }

    @Override
    public WorkerCapabilityReportSnapshot reportWorkerCapability(WorkerCapabilityReportRequest request) {
        Objects.requireNonNull(request, "request");
        WorkerCapabilityReportResult result = requireStartedWorkerControlService()
                .applyWorkerCapabilityReport(WorkerCapabilityReport.builder(
                                request.workerId(),
                                request.capabilityVersion())
                        .availableEventCodes(request.availableEventCodes())
                        .schedulingAttributes(request.schedulingAttributes())
                        .agentVersion(request.agentVersion())
                        .build());
        return new WorkerCapabilityReportSnapshot(
                result.status().name(),
                result.workerId(),
                result.capabilityVersion(),
                result.success(),
                result.snapshotChanged(),
                result.reason()
        );
    }

    @Override
    public WorkerStateReportSnapshot reportWorkerState(WorkerStateReportRequest request) {
        Objects.requireNonNull(request, "request");
        WorkerStateProjectionResult result = requireStartedWorkerControlService()
                .applyWorkerStateReport(WorkerStateReport.builder(
                                request.workerId(),
                                request.stateVersion(),
                                request.state())
                        .reason(request.reason())
                        .observedAt(request.observedAt())
                        .attributes(request.attributes())
                        .build());
        return new WorkerStateReportSnapshot(
                result.status().name(),
                result.workerId(),
                result.stateVersion(),
                result.success(),
                result.projectionChanged(),
                result.reason(),
                toWorkerStateProjectionSnapshot(result.projection())
        );
    }

    @Override
    public WorkerStateProjectionSnapshot getWorkerStateProjection(String workerId) {
        return toWorkerStateProjectionSnapshot(requireStartedWorkerControlService()
                .workerStateProjection(requireWorkerId(workerId))
                .orElse(null));
    }

    @Override
    public List<WorkerStateProjectionSnapshot> listWorkerStateProjections() {
        return requireStartedWorkerControlService().workerStateProjections().stream()
                .map(this::toWorkerStateProjectionSnapshot)
                .toList();
    }

    @Override
    public WorkerCommandResultSnapshot requestWorkerCommand(WorkerCommandSubmitRequest request) {
        Objects.requireNonNull(request, "request");
        WorkerCommandLifecycleResult result = requireStartedWorkerControlService()
                .requestWorkerCommand(WorkerCommandRequest.builder(
                                request.commandId(),
                                request.workerId(),
                                request.commandType())
                        .requester(request.requester())
                        .reason(request.reason())
                        .idempotencyKey(request.idempotencyKey())
                        .deadlineEpochMillis(request.deadlineEpochMillis())
                        .payload(request.payload())
                        .build());
        return toWorkerCommandResultSnapshot(result);
    }

    @Override
    public WorkerCommandResultSnapshot acknowledgeWorkerCommand(WorkerCommandAcknowledgementRequest request) {
        Objects.requireNonNull(request, "request");
        WorkerCommandLifecycleResult result = requireStartedWorkerControlService()
                .applyWorkerCommandAcknowledgement(new WorkerCommandAcknowledgement(
                        request.commandId(),
                        parseWorkerCommandStatus(request.status()),
                        request.reason()
                ));
        return toWorkerCommandResultSnapshot(result);
    }

    @Override
    public List<WorkerCommandSnapshot> pullWorkerCommands(String workerId, int maxCommands) {
        return requireStartedWorkerControlService()
                .claimPendingWorkerCommands(requireWorkerId(workerId), Math.max(1, maxCommands))
                .stream()
                .map(this::toWorkerCommandSnapshot)
                .toList();
    }

    @Override
    public WorkerCommandSnapshot getWorkerCommand(String commandId) {
        return toWorkerCommandSnapshot(requireStartedWorkerControlService()
                .workerCommand(requireCommandId(commandId))
                .orElse(null));
    }

    @Override
    public List<WorkerCommandSnapshot> listWorkerCommandsForWorker(String workerId) {
        return requireStartedWorkerControlService()
                .workerCommandsForWorker(requireWorkerId(workerId))
                .stream()
                .map(this::toWorkerCommandSnapshot)
                .toList();
    }

    @Override
    public TaskStageEvidenceSnapshot reportTaskStageEvidence(TaskStageEvidenceRequest request) {
        Objects.requireNonNull(request, "request");
        TaskStageEvidenceResult result = requireStartedTaskStageEvidenceService()
                .applyEvidence(
                        request.taskId(),
                        request.messageId(),
                        request.stageName(),
                        request.stageVersion(),
                        request.stageStatus(),
                        request.detail(),
                        request.observedAt(),
                        request.attributes());
        return new TaskStageEvidenceSnapshot(
                result.status().name(),
                result.taskId(),
                result.messageId(),
                result.stageName(),
                result.stageVersion(),
                result.success(),
                result.projectionChanged(),
                result.reason(),
                toTaskStageProjectionSnapshot(result.projection())
        );
    }

    @Override
    public TaskStageProjectionSnapshot getTaskStageProjection(String taskId, String messageId, String stageName) {
        return toTaskStageProjectionSnapshot(requireStartedTaskStageEvidenceService()
                .projection(requireTaskId(taskId), requireMessageId(messageId), requireStageName(stageName))
                .orElse(null));
    }

    @Override
    public List<TaskStageProjectionSnapshot> listTaskStageProjections(String taskId, String messageId) {
        return requireStartedTaskStageEvidenceService()
                .projectionsForMessage(requireTaskId(taskId), requireMessageId(messageId))
                .stream()
                .map(this::toTaskStageProjectionSnapshot)
                .toList();
    }

    @Override
    public String getWorkerAdapterId(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        WorkerResourceRecord worker = loadWorker(normalizedWorkerId);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + normalizedWorkerId);
        }
        if (delegate.getTransportRuntimeRegistry() != null) {
            return delegate.getTransportRuntimeRegistry().resolveWorkerAdapterId(normalizedWorkerId);
        }
        if (worker.adapterId() == null || worker.adapterId().isBlank()) {
            throw new IllegalStateException("Worker adapterId is not set: " + worker.workerId());
        }
        return worker.adapterId().trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String getWorkerTransportHint(String workerId) {
        WorkerResourceRecord worker = loadWorker(requireWorkerId(workerId));
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + requireWorkerId(workerId));
        }
        String transportHint = WorkerTransportHints.normalize(worker.onlineStrategy());
        if (transportHint == null && delegate.getTransportRuntimeRegistry() != null) {
            transportHint = delegate.getTransportRuntimeRegistry().resolveWorkerTransportHint(worker.workerId());
        }
        if (transportHint == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy is not set: " + worker.workerId());
        }
        return transportHint;
    }

    @Override
    public WorkerSnapshot getWorker(String workerId) {
        return toWorkerSnapshot(loadWorker(workerId));
    }

    @Override
    public List<WorkerSnapshot> getAllWorkers() {
        return requireStartedWorkerResourceRuntime().workers().stream()
                .map(this::toWorkerSnapshot)
                .toList();
    }

    @Override
    public List<WorkerGroupSnapshot> listWorkerGroups() {
        requireStartedEngine();
        return delegate.getEngine().getConfig().getWorkerResourceRuntime().workerGroups().stream()
                .map(this::toWorkerGroupSnapshot)
                .toList();
    }

    @Override
    public List<AdapterNodeSnapshot> listAdapterNodes() {
        requireStartedEngine();
        return delegate.getEngine().getConfig().getWorkerResourceRuntime().adapterNodes().stream()
                .map(this::toAdapterNodeSnapshot)
                .toList();
    }

    @Override
    public List<NodeGroupBindingSnapshot> listNodeGroupBindings() {
        requireStartedEngine();
        return delegate.getEngine().getConfig().getWorkerResourceRuntime().nodeGroupBindings().stream()
                .map(this::toNodeGroupBindingSnapshot)
                .toList();
    }

    @Override
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
    public TaskPullResult pollTasksResult(String workerId, int maxMessages, long timeoutMillis) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than 0");
        }
        return externalPullWorkerSession(workerId).pollResult(maxMessages, timeoutMillis);
    }

    @Override
    public List<TaskDispatchItem> pollTasks(String workerId, int maxMessages, long timeoutMillis) {
        return pollTasksResult(workerId, maxMessages, timeoutMillis).getDispatchViews();
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

    @Override
    public boolean isWorkerOnline(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        if (delegate.getWorkerPresenceStore() != null) {
            return delegate.getWorkerPresenceStore().isWorkerOnline(normalizedWorkerId);
        }
        WorkerResourceRecord worker = loadWorker(normalizedWorkerId);
        return worker != null && workerStatusAvailable(worker.statusName());
    }

    @Override
    public boolean updateWorkerSupportedProjects(String workerId, List<String> supportedProjects) {
        WorkerResourceRuntime workerRuntime = requireStartedWorkerResourceRuntime();
        WorkerResourceRecord worker = workerRuntime.worker(requireWorkerId(workerId)).orElse(null);
        if (worker == null) {
            return false;
        }
        return workerRuntime.updateWorker(new WorkerResourceRecord(
                worker.workerId(),
                worker.statusName(),
                worker.agentVersion(),
                worker.lastHeartbeat(),
                normalizedProjectCodes(supportedProjects),
                worker.supportedEventCodes(),
                worker.workerGroupId(),
                worker.adapterNodeId(),
                worker.adapterId(),
                worker.onlineStrategy(),
                worker.maxConcurrentWork(),
                worker.attributes(),
                worker.createTime(),
                worker.updateTime()
        ));
    }

    @Override
    public void registerProject(ProjectDefinition projectDefinition) {
        ProjectDefinition normalized = Objects.requireNonNull(projectDefinition, "projectDefinition");
        bootstrapProjectCatalogRegistry.registerProject(normalized);
        registerProjectIntoCore(normalized);
        syncProjectScopeIntoDefinitions(normalized);
    }

    @Override
    public void registerEventDefinition(EventDefinition definition) {
        registerEventDefinitionInternal(Objects.requireNonNull(definition, "definition"));
    }

    @Override
    public List<ProjectDefinition> listProjects() {
        return bootstrapProjectCatalogRegistry.listProjects();
    }

    @Override
    public ProjectDefinition getProject(String projectCode) {
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
    public ControlPlaneCatalog catalog() {
        return controlPlaneCatalogView;
    }

    @Override
    public void registerSubmitter(SubmitterRegistration submitterRegistration) {
        submitterRegistry.register(submitterRegistration);
    }

    @Override
    public List<SubmitterProfile> listSubmitters() {
        return submitterRegistry.listSubmitters();
    }

    @Override
    public SubmitterProfile getSubmitter(String principalId) {
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
                .priorityClass(existing != null ? existing.getPriorityClass() : null)
                .responseMode(existing != null ? existing.getResponseMode() : null)
                .targetScope(existing != null ? existing.getTargetScope() : null)
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
                .priorityClass(normalized.getPriorityClass())
                .responseMode(normalized.getResponseMode())
                .targetScope(normalized.getTargetScope())
                .handler(resolveDefinitionHandler(normalized))
                .build();
        eventHandlerCache.put(merged.getCode(), merged.getHandler());
        eventRuntime.registerOrReplace(toCoreDescriptor(merged), toCoreHandler(merged.getHandler()));
        refreshDerivedEventDefinitionCache();
    }

    private EventHandler existingHandler(String eventCode) {
        return eventHandlerCache.get(eventCode);
    }

    private void syncProjectScopeIntoDefinitions(ProjectDefinition projectDefinition) {
        if (projectDefinition.getEventCodes() == null || projectDefinition.getEventCodes().isEmpty()) {
            return;
        }
        for (String eventCode : projectDefinition.getEventCodes()) {
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
                            .projectCodes(mergeProjectCodes(existing.getProjectCodes(), List.of(projectDefinition.getCode())))
                            .priorityClass(existing.getPriorityClass())
                            .responseMode(existing.getResponseMode())
                            .targetScope(existing.getTargetScope())
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
        for (ProjectDefinition projectDefinition : bootstrapProjectCatalogRegistry.listProjects()) {
            if (projectDefinition.getEventCodes().contains(eventCode)) {
                projectCodes.add(projectDefinition.getCode());
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

    private void resolveWorkerGroupSelectorForAppend(String taskId, String eventCode) {
        String normalizedEventCode = blankToNull(eventCode);
        MassEngine engine = delegate.getEngine();
        var config = engine == null ? null : engine.getConfig();
        if (engine == null || !engine.isRunning() || config == null
                || config.getTaskQueryService() == null
                || config.getTaskCommandService() == null
                || config.getWorkerResourceRuntime() == null) {
            return;
        }
        Task task = config.getTaskQueryService().getTask(taskId);
        if (task == null) {
            return;
        }
        WorkerGroupSelectorResolver.requireExplicitTargetWorkerBinding(task.getSharedConfig());
        if (!TaskSharedConfig.workerGroupSelector(task).isEmpty() || normalizedEventCode == null) {
            return;
        }
        Map<String, Object> sharedConfig = WorkerGroupSelectorResolver.resolveEventBackedSelector(
                task.getSharedConfig(),
                task.getProject(),
                normalizedEventCode,
                config.getWorkerResourceRuntime().workerGroups()
        );
        task.setSharedConfig(sharedConfig);
        config.getTaskCommandService().updateTask(task);
    }

    private EventHandler resolveDefinitionHandler(EventDefinition definition) {
        if (definition.getHandler() != null) {
            return definition.getHandler();
        }
        return (request, principal) -> unsupportedCatalogTaskEvent(request, definition);
    }

    private EventResponse unsupportedCatalogTaskEvent(EventRequest request, EventDefinition definition) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(definition, "definition");
        if (!definition.getTaskModes().isEmpty()) {
            return EventResponse.failure(
                    "TASK_BACKED_EVENT_REQUIRES_TASK_API",
                    "Task-backed event " + definition.getCode()
                            + " must use createTaskShell + appendTaskItems + sealTask instead of dispatchEvent",
                    request.getRequestId()
            );
        }
        return EventResponse.failure(
                "EVENT_HANDLER_NOT_REGISTERED",
                "No runtime event handler registered for event: " + definition.getCode(),
                request.getRequestId()
        );
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> rawMap) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
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

    private WorkerRegistration resolveWorkerRegistration(CoreEventRequest request) {
        Object embedded = request.getPayload().get("request");
        if (embedded instanceof WorkerRegistration registration) {
            return normalizeWorkerRegistration(registration);
        }
        Map<String, Object> payload = request.getPayload();
        return normalizeWorkerRegistration(WorkerRegistration.builder()
                .workerId(readRequiredString(payload, "workerId"))
                .adapterNodeId(readString(payload, "adapterNodeId", null))
                .workerGroupId(readString(payload, "workerGroupId", null))
                .adapterId(readString(payload, "adapterId", null))
                .transportHint(readString(payload, "transportHint", null))
                .maxConcurrentWork(readInt(readString(payload, "maxConcurrentWork", null), 1))
                .attributes(readStringMap(payload.get("attributes")))
                .build());
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

    private PrincipalContext internalPrincipal(String userId) {
        return PrincipalContext.internalService("sdk-internal", userId);
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private String requireCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        return commandId.trim();
    }

    private String requireStageName(String stageName) {
        if (stageName == null || stageName.isBlank()) {
            throw new IllegalArgumentException("stageName must not be blank");
        }
        return stageName.trim();
    }

    private PullWorkerSession externalPullWorkerSession(String workerId) {
        return pullWorker(requireWorkerId(workerId));
    }

    private WorkerGroupRecord toWorkerGroupRecord(WorkerGroupDeclaration declaration) {
        List<WorkerEventBinding> declaredBindings = declaration.getEventBindings();
        if (declaredBindings.isEmpty()) {
            throw new IllegalArgumentException("eventBindings is required");
        }
        List<EventBinding> eventBindings = new ArrayList<>(declaredBindings.size());
        for (WorkerEventBinding binding : declaredBindings) {
            EventDefinition definition = requireEnabledEventDefinition(binding.getEventCode());
            eventBindings.add(EventBinding.of(definition.getCode(), resolveWorkerBindingProjects(definition, binding)));
        }
        return WorkerGroupRecord.builder(declaration.getGroupId())
                .eventBindings(eventBindings)
                .defaultAttributes(declaration.getDefaultAttributes())
                .defaultMaxConcurrentWork(declaration.getDefaultMaxConcurrentWork())
                .build();
    }

    private WorkerRegistration normalizeWorkerRegistration(WorkerRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        String normalizedTransportHint =
                WorkerTransportHints.normalize(requireNonBlank(registration.getTransportHint(), "transportHint"));
        String resolvedAdapterId = resolveRegistrationAdapterId(registration.getAdapterId(), normalizedTransportHint);
        String workerGroupId = blankToNull(registration.getWorkerGroupId());
        String adapterNodeId = blankToNull(registration.getAdapterNodeId());
        if (workerGroupId != null && adapterNodeId == null) {
            throw new IllegalArgumentException("adapterNodeId must not be blank when workerGroupId is provided");
        }

        return WorkerRegistration.builder()
                .workerId(registration.getWorkerId())
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .adapterId(resolvedAdapterId)
                .transportHint(normalizedTransportHint)
                .maxConcurrentWork(registration.getMaxConcurrentWork())
                .attributes(registration.getAttributes())
                .build();
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
            ProjectDefinition projectDefinition = getProject(projectCode);
            if (projectDefinition == null) {
                throw new IllegalArgumentException("Unsupported worker project: " + projectCode);
            }
            if (!projectDefinition.isEnabled()) {
                throw new IllegalArgumentException("Worker project is disabled: " + projectCode);
            }
            if (!definitionScope.contains(projectDefinition.getCode())) {
                throw new IllegalArgumentException("Worker project " + projectDefinition.getCode()
                        + " is outside event scope: " + definition.getCode());
            }
            resolvedProjects.add(projectDefinition.getCode());
        }
        return List.copyOf(resolvedProjects);
    }

    private void registerEnabledCatalogProjectsIntoCore() {
        for (ProjectDefinition projectDefinition : bootstrapProjectCatalogRegistry.listProjects()) {
            registerProjectIntoCore(projectDefinition);
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
                .priorityClass(definition.getPriorityClass())
                .responseMode(definition.getResponseMode())
                .deliveryAcknowledgementMode(definition.getDeliveryAcknowledgementMode())
                .convergenceMode(definition.getConvergenceMode())
                .targetScope(definition.getTargetScope())
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
                .priorityClass(descriptor.getPriorityClass())
                .responseMode(descriptor.getResponseMode())
                .deliveryAcknowledgementMode(descriptor.getDeliveryAcknowledgementMode())
                .convergenceMode(descriptor.getConvergenceMode())
                .targetScope(descriptor.getTargetScope())
                .handler(eventHandlerCache.get(descriptor.getEvent()))
                .build();
    }

    private List<String> resolveProjectCodesForEvent(String eventCode, Collection<String> seedProjectCodes) {
        LinkedHashSet<String> projectCodes = new LinkedHashSet<>();
        if (seedProjectCodes != null) {
            projectCodes.addAll(seedProjectCodes);
        }
        for (ProjectDefinition projectDefinition : bootstrapProjectCatalogRegistry.listProjects()) {
            if (projectDefinition.getEventCodes().contains(eventCode)) {
                projectCodes.add(projectDefinition.getCode());
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
                // Keep the derived catalog projection tolerant of unknown internal descriptor values.
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
                // Keep the derived catalog projection tolerant of unknown internal descriptor values.
            }
        }
        return List.copyOf(taskModes);
    }

    private List<EventDefinition> projectEventDefinitionsFromRuntime() {
        return eventRuntime.listDescriptors().stream()
                .map(this::toEventDefinition)
                .toList();
    }

    private void registerProjectIntoCore(ProjectDefinition projectDefinition) {
        Objects.requireNonNull(projectDefinition, "projectDefinition");
        ProjectRegistry.register(projectDefinition.getCode(), projectDefinition.getName(), projectDefinition.isEnabled());
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

    /**
     * Registers a listener that fires synchronously when a task message reaches its
     * logically final state (success or exhausted retries). Safe to call before
     * {@link #start()} 闂?the listener is registered on the engine command/event
     * surface which exists independent of engine lifecycle.
     */
    public void addTaskWorkFinalListener(TaskWorkFinalListener listener) {
        Objects.requireNonNull(listener, "listener");
        requireStartedTaskEvents().addTaskWorkLogicallyFinalListener((task, event) -> listener.onTaskWorkFinal(
                new TaskWorkFinalNotification(
                        event.taskId(),
                        task == null ? Map.of() : task.getSharedConfig(),
                        new TaskWorkFinalSnapshot(
                                event.taskId(),
                                event.messageId(),
                                event.status() == null ? null : event.status().name(),
                                event.finalReason() == null ? null : event.finalReason().name(),
                                event.retryCount(),
                                event.errorCode(),
                                event.errorMessage(),
                                event.payloadRef(),
                                event.output()
                        )
                )
        ));
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

    private TaskResultRuntime requireStartedTaskResultRuntime() {
        TaskResultRuntime runtime = requireStartedEngine().getConfig().getTaskResultRuntime();
        if (runtime == null) {
            throw new IllegalStateException("Task result runtime is unavailable for this SDK application");
        }
        return runtime;
    }

    private TaskEventService requireStartedTaskEvents() {
        TaskEventService taskEvents = requireStartedEngine().getConfig().getTaskEventService();
        if (taskEvents == null) {
            throw new IllegalStateException("Task event service is unavailable for this SDK application");
        }
        return taskEvents;
    }

    private WorkerResourceRuntime requireStartedWorkerResourceRuntime() {
        WorkerResourceRuntime workerRuntime = requireStartedEngine().getConfig().getWorkerResourceRuntime();
        if (workerRuntime == null) {
            throw new IllegalStateException("Worker resource runtime is unavailable for this SDK application");
        }
        return workerRuntime;
    }

    private RuleStorage requireStartedRuleStorage() {
        RuleStorage ruleStorage = requireStartedEngine().getConfig().getRuleStorage();
        if (ruleStorage == null) {
            throw new IllegalStateException("Rule storage is unavailable for this SDK application");
        }
        return ruleStorage;
    }

    private WorkerControlService requireStartedWorkerControlService() {
        WorkerControlService service = requireStartedEngine().getConfig().getWorkerControlService();
        if (service == null) {
            throw new IllegalStateException("Worker control service is unavailable for this SDK application");
        }
        return service;
    }

    private TaskStageEvidenceService requireStartedTaskStageEvidenceService() {
        TaskStageEvidenceService service = requireStartedEngine().getConfig().getTaskStageEvidenceService();
        if (service == null) {
            throw new IllegalStateException("Task stage evidence service is unavailable for this SDK application");
        }
        return service;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException("map key");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private boolean workerStatusAvailable(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return false;
        }
        try {
            return WorkerStatus.valueOf(statusName.trim().toUpperCase(Locale.ROOT)).isAvailable();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private WorkerResourceRecord loadWorker(String workerId) {
        return requireStartedWorkerResourceRuntime().worker(workerId).orElse(null);
    }

    private boolean executeBlockTask(TaskCommandService taskCommands, String taskId, Task currentTask) {
        if (taskCommands.blockTask(taskId)) {
            return true;
        }
        return currentTask != null
                && currentTask.getStatus() == com.xa.mass.base.enums.task.TaskStatus.NEW
                && taskCommands.rejectTask(taskId);
    }

    private TaskCommandResult toTaskCommandResult(String taskId,
                                                  String command,
                                                  boolean accepted,
                                                  boolean taskExists,
                                                  Task task,
                                                  String failureReason,
                                                  String reasonCode) {
        return new TaskCommandResult(
                taskId,
                command,
                accepted,
                taskExists,
                task != null ? enumName(task.getStatus()) : null,
                task != null ? enumName(task.getIntakeStatus()) : null,
                task != null ? enumName(task.getTerminalReason()) : null,
                task != null ? enumName(task.getHoldReason()) : null,
                failureReason,
                reasonCode
        );
    }

    private String normalizeTaskCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        return command.trim().toUpperCase(Locale.ROOT);
    }

    private WorkerCommandStatus parseWorkerCommandStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("worker command status is required");
        }
        return WorkerCommandStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    }

    private WorkerCommandResultSnapshot toWorkerCommandResultSnapshot(WorkerCommandLifecycleResult result) {
        if (result == null) {
            return null;
        }
        return new WorkerCommandResultSnapshot(
                result.code().name(),
                result.success(),
                result.previousStatus() == null ? null : result.previousStatus().name(),
                result.currentStatus() == null ? null : result.currentStatus().name(),
                result.reason(),
                toWorkerCommandSnapshot(result.record())
        );
    }

    private WorkerCommandSnapshot toWorkerCommandSnapshot(WorkerCommandRecord record) {
        if (record == null) {
            return null;
        }
        return new WorkerCommandSnapshot(
                record.commandId(),
                record.workerId(),
                record.commandType(),
                record.status() == null ? null : record.status().name(),
                record.requester(),
                record.reason(),
                record.idempotencyKey(),
                record.deadlineEpochMillis(),
                record.payload(),
                record.statusReason(),
                record.deliveryAttemptCount(),
                record.lastDeliveryAttemptAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private WorkerStateProjectionSnapshot toWorkerStateProjectionSnapshot(WorkerStateProjection projection) {
        if (projection == null) {
            return null;
        }
        return new WorkerStateProjectionSnapshot(
                projection.workerId(),
                projection.stateVersion(),
                projection.state(),
                projection.reason(),
                projection.observedAt(),
                projection.acceptedAt()
        );
    }

    private TaskStageProjectionSnapshot toTaskStageProjectionSnapshot(TaskStageProjection projection) {
        if (projection == null) {
            return null;
        }
        return new TaskStageProjectionSnapshot(
                projection.taskId(),
                projection.messageId(),
                projection.stageName(),
                projection.stageVersion(),
                projection.stageStatus(),
                projection.detail(),
                projection.observedAt(),
                projection.acceptedAt()
        );
    }

    private WorkerSnapshot toWorkerSnapshot(WorkerResourceRecord worker) {
        if (worker == null) {
            return null;
        }
        WorkerGroupRecord group = resolveWorkerGroup(worker.workerGroupId());
        List<String> supportedProjects = group != null
                ? List.copyOf(group.projectCodes())
                : worker.supportedProjects();
        List<String> supportedEventCodes = group != null
                ? List.copyOf(group.eventCodes())
                : worker.supportedEventCodes();
        List<WorkerEventBinding> eventBindings = group != null
                ? toWorkerEventBindings(group)
                : deriveWorkerEventBindings(worker);
        return new WorkerSnapshot(
                worker.workerId(),
                worker.statusName(),
                worker.agentVersion(),
                worker.lastHeartbeat(),
                supportedProjects,
                supportedEventCodes,
                eventBindings,
                worker.workerGroupId(),
                worker.adapterNodeId(),
                worker.adapterId(),
                worker.onlineStrategy(),
                worker.maxConcurrentWork(),
                worker.attributes(),
                worker.createTime(),
                worker.updateTime()
        );
    }

    private WorkerGroupSnapshot toWorkerGroupSnapshot(WorkerGroupRecord group) {
        return new WorkerGroupSnapshot(
                group.groupId(),
                toWorkerEventBindings(group),
                List.copyOf(group.projectCodes()),
                group.defaultAttributes(),
                group.defaultMaxConcurrentWork()
        );
    }

    private AdapterNodeSnapshot toAdapterNodeSnapshot(AdapterNodeRecord node) {
        return new AdapterNodeSnapshot(
                node.adapterNodeId(),
                node.adapterType(),
                node.adapterVersion(),
                node.endpointId(),
                node.enabled(),
                node.online(),
                instantString(node.registeredAt()),
                instantString(node.lastSeenAt()),
                node.attributes()
        );
    }

    private NodeGroupBindingSnapshot toNodeGroupBindingSnapshot(NodeGroupBindingRecord binding) {
        return new NodeGroupBindingSnapshot(
                binding.adapterNodeId(),
                binding.groupId(),
                binding.pluginVersion(),
                binding.deploymentVersion(),
                binding.enabled(),
                binding.draining(),
                instantString(binding.registeredAt()),
                instantString(binding.updatedAt()),
                binding.attributes()
        );
    }

    private String instantString(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    private WorkerGroupRecord resolveWorkerGroup(String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            return null;
        }
        return requireStartedEngine()
                .getConfig()
                .getWorkerResourceRuntime()
                .workerGroup(workerGroupId)
                .orElse(null);
    }

    private List<WorkerEventBinding> toWorkerEventBindings(WorkerGroupRecord group) {
        if (group == null || group.eventBindings().isEmpty()) {
            return List.of();
        }
        List<WorkerEventBinding> bindings = new ArrayList<>(group.eventBindings().size());
        for (EventBinding binding : group.eventBindings()) {
            bindings.add(WorkerEventBinding.builder()
                    .eventCode(binding.eventCode())
                    .projectCodes(binding.projectCodes())
                    .build());
        }
        return List.copyOf(bindings);
    }

    private List<WorkerEventBinding> deriveWorkerEventBindings(WorkerResourceRecord worker) {
        if (worker == null) {
            return List.of();
        }
        List<String> supportedEventCodes = worker.supportedEventCodes();
        if (supportedEventCodes == null || supportedEventCodes.isEmpty()) {
            return List.of();
        }
        List<WorkerEventBinding> bindings = new ArrayList<>(supportedEventCodes.size());
        for (String eventCode : supportedEventCodes) {
            if (eventCode == null || eventCode.isBlank()) {
                continue;
            }
            EventDefinition definition = controlPlaneCatalogView.getEvent(eventCode);
            bindings.add(WorkerEventBinding.builder()
                    .eventCode(eventCode)
                    .projectCodes(definition == null ? List.of() : definition.getProjectCodes())
                    .build());
        }
        return bindings.isEmpty() ? List.of() : List.copyOf(bindings);
    }

    private TaskShellSnapshot toTaskShellSnapshot(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskShellSnapshot(
                task.getTid(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUser() == null ? null : task.getUser().getUserId(),
                enumName(task.getContract()),
                task.getSourceRef()
        );
    }

    private TaskStateSnapshot toTaskStateSnapshot(Task task) {
        return new TaskStateSnapshot(
                task.getTid(),
                enumName(task.getStatus()),
                enumName(task.getTerminalReason()),
                enumName(task.getIntakeStatus())
        );
    }

    private TaskAccessSnapshot toTaskAccessSnapshot(Task task) {
        return new TaskAccessSnapshot(
                task.getTid(),
                task.getProject(),
                copyMap(task.getSharedConfig()),
                enumName(task.getIntakeStatus())
        );
    }

    private TaskSummarySnapshot toTaskSummarySnapshot(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskSummarySnapshot(
                task.getTid(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUser() == null ? null : task.getUser().getUserId(),
                enumName(task.getContract()),
                enumName(task.getStatus()),
                enumName(task.getTerminalReason()),
                toTaskExecutionOptions(task.getExecutionSpec()),
                task.getTaskSuccessNumber(),
                task.getTaskEligibleNumber(),
                task.getUpdateTime()
        );
    }

    private TaskDetailSnapshot toTaskDetailSnapshot(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskDetailSnapshot(
                task.getTid(),
                task.getTenantId(),
                task.getTaskName(),
                enumName(task.getContract()),
                task.getProject(),
                enumName(task.getStatus()),
                task.getTaskTargetNumber(),
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                task.getTaskNonSuccessNumber(),
                task.getMinRequiredWorkerCount(),
                task.getPeakAssignedWorkerCount(),
                copyMap(task.getSharedConfig()),
                enumName(task.getHoldReason()),
                toTaskExecutionOptions(task.getExecutionSpec()),
                task.getSourceRef(),
                enumName(task.getIntakeStatus()),
                task.getUser() == null ? null : task.getUser().getUserId(),
                task.getCreateTime(),
                task.getUpdateTime(),
                task.getStartTime(),
                task.getEndTime(),
                enumName(task.getTerminalReason())
        );
    }

    private TaskResultWindowSnapshot toTaskResultWindowSnapshot(TaskResultWindow window) {
        return new TaskResultWindowSnapshot(
                window.taskId(),
                window.items().stream().map(this::toTaskResultItemSnapshot).toList(),
                window.nextAfterSeq(),
                window.hasMore(),
                window.totalVisible()
        );
    }

    private TaskResultItemSnapshot toTaskResultItemSnapshot(TaskResultRuntimeRow row) {
        return new TaskResultItemSnapshot(
                row.seq(),
                row.messageId(),
                row.eventCode(),
                row.status(),
                row.finalReason(),
                row.retryCount(),
                row.maxRetryCount(),
                row.workerId(),
                row.batchId(),
                row.attemptId(),
                row.payloadRef(),
                row.createTime(),
                row.assignedTime(),
                row.startTime(),
                row.completeTime(),
                row.updateTime(),
                row.errorCode(),
                row.errorMessage(),
                row.output()
        );
    }

    private TaskWorkFinalSnapshot toTaskWorkFinalSnapshot(TaskResultRuntimeRow row) {
        return new TaskWorkFinalSnapshot(
                row.taskId(),
                row.messageId(),
                row.status(),
                row.finalReason(),
                row.retryCount(),
                row.errorCode(),
                row.errorMessage(),
                row.payloadRef(),
                row.output()
        );
    }

    private TaskExecutionOptions toTaskExecutionOptions(com.xa.mass.base.model.TaskExecutionSpec spec) {
        TaskExecutionOptions view = new TaskExecutionOptions();
        if (spec == null) {
            return view;
        }
        view.setProfile(enumName(spec.getProfile()));
        view.setWorkloadClass(enumName(spec.getWorkloadClass()));
        view.setBatchSize(spec.getBatchSize());
        view.setMaxRuntimeSeconds(spec.getMaxRuntimeSeconds());
        view.setDefaultMaxRetryCount(spec.getDefaultMaxRetryCount());
        view.setForeground(spec.isForeground());
        return view;
    }

    private com.xa.mass.base.enums.task.TaskStatus parseTaskStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return com.xa.mass.base.enums.task.TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private com.xa.mass.base.enums.task.TaskTerminalReason parseTaskTerminalReason(
            String value,
            com.xa.mass.base.enums.task.TaskTerminalReason defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return com.xa.mass.base.enums.task.TaskTerminalReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
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

    private List<Map<String, Object>> requireAppendItems(List<Object> items, String batchEventCode) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("SDK append items must be JSON object maps");
            }
            Map<String, Object> normalizedMap = stringObjectMap(rawMap);
            if (batchEventCode != null && !batchEventCode.isBlank() && !normalizedMap.containsKey("eventCode")) {
                LinkedHashMap<String, Object> merged = new LinkedHashMap<>(normalizedMap);
                merged.put("eventCode", batchEventCode.trim());
                normalized.add(Map.copyOf(merged));
            } else {
                normalized.add(normalizedMap);
            }
        }
        return List.copyOf(normalized);
    }
}
