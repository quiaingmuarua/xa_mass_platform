package com.xa.mass.starter.config;

import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.engine.EngineRuntimeKernel;
import com.xa.mass.engine.EngineRuntimeKernelConfig;
import com.xa.mass.engine.ExponentialPollingIdleBackoffPolicy;
import com.xa.mass.engine.PollingIdleBackoffPolicy;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskCommandPort;
import com.xa.mass.engine.TaskEventListenerRegistrar;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskQueryPort;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.engine.TaskRuntimeServingLane;
import com.xa.mass.engine.TaskWorkAttemptIdSupport;
import com.xa.mass.engine.TaskShellLifecycleMaintenancePort;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.WorkerControlRuntime;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.TenantConstants;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.stage.TaskStageEvidenceOwner;
import com.xa.mass.engine.stage.TaskStageEvidenceService;
import com.xa.mass.engine.rules.MatchingRuleEvaluator;
import com.xa.mass.engine.rules.MatchingRuleSetProvider;
import com.xa.mass.engine.rules.RegistryBackedMatchingRuleEvaluator;
import com.xa.mass.engine.rules.RuleConfig;
import com.xa.mass.engine.rules.RuleEvaluatorRegistry;
import com.xa.mass.engine.rules.RuleEvaluatorRegistries;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetDefinition;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeLifecycleQuery;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeStore;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.worker.runtime.control.WorkerDispatchEligibilityRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchRecoveryRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.report.WorkerReportRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockRuntime;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
import com.xa.mass.starter.EngineRuntimeBridge;
import com.xa.mass.task.runtime.command.TaskRuntimeCommandPort;
import com.xa.mass.task.runtime.starter.TaskRuntimeBootstrapConfig;
import com.xa.mass.task.runtime.starter.TaskRuntimeHandle;
import com.xa.mass.task.runtime.starter.TaskRuntimeLoop;
import com.xa.mass.task.runtime.starter.TaskRuntimePortSet;
import com.xa.mass.task.runtime.starter.TaskRuntimeStarter;
import com.xa.mass.task.runtime.starter.TaskReadViewPort;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;
import com.xa.mass.worker.runtime.resource.WorkerNodeBindingRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runtime engine configuration.
 *
 * <p>This config owns the embedded engine assembly state. Downstream runtime
 * code should prefer the derived command/query/facade/port surfaces exposed by
 * this config rather than carrying raw engine assembly internals. Worker/rule
 * managers are derived helpers over storage contracts, not independent config
 * slots with their own truth.
 */
public class EngineConfig {

    private static final Function<String, WorkerReachabilityState> UNKNOWN_WORKER_REACHABILITY =
            workerId -> WorkerReachabilityState.UNKNOWN;

    private boolean enabled = true;
    private int workerThreads = 8;

    private TaskManager taskManager;
    private TaskCommandPort taskCommandPort;
    private TaskEventService taskEventService;
    private TaskQueryPort taskQueryPort;
    private TaskResultIngestFacade taskResultIngestFacade;
    private TaskAssignmentRuntimePort taskAssignmentRuntimePort;
    private TaskLeaseMaintenancePort taskLeaseMaintenancePort;
    private TaskDispatchWakeupPort taskDispatchWakeupPort;
    private TaskShellLifecycleMaintenancePort taskShellLifecycleMaintenancePort;
    private TaskRuntimeRecoveryPort taskRuntimeRecoveryPort;
    private TaskRuntimeServingLane taskRuntimeServingLane;
    private TaskReadViewPort taskReadViewPort;
    private final TaskReadViewProjectionStore taskReadViewProjection = new TaskReadViewProjectionStore();
    private boolean taskReadProjectionListenersInstalled;
    private TraceEventLogger traceEventLogger;
    private TaskShellStore taskShellStore;
    private TaskRuntimeBootstrapConfig taskRuntimeBootstrapConfig = TaskRuntimeBootstrapConfig.memory();
    private TaskRuntimeHandle taskRuntimeHandle;
    private Function<String, WorkerReachabilityState> workerReachabilityLookup = UNKNOWN_WORKER_REACHABILITY;
    private Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> workerDeliveryTargetResolver =
            selectedWorkerId -> Optional.empty();
    private boolean workerDeliveryTargetResolverExplicitlyConfigured;
    private WorkerDeclarationStore workerDeclarationStore = new InMemoryWorkerDeclarationStore();
    private WorkerRegistry workerRegistry;
    private WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime;
    private WorkerManager workerManager;
    private WorkerCommandLifecycleOwner workerCommandLifecycleOwner = new WorkerCommandLifecycleOwner();
    private WorkerDispatchEligibilityRuntime workerDispatchEligibilityRuntime;
    private WorkerStateProjectionRuntime workerStateProjectionRuntime = new WorkerStateProjectionOwner();
    private WorkerControlRuntime workerControlRuntime;
    private TaskStageEvidenceOwner taskStageEvidenceOwner = new TaskStageEvidenceOwner();
    private TaskStageEvidenceService taskStageEvidenceService;
    private AssignmentDiagnosticRecorder recordService = new AssignmentRecordService();
    private RuleStorage ruleStorage = new InMemoryRuleStorage();
    private RuleEvaluatorRegistry<Map<String, Object>> ruleEvaluatorRegistry =
            RuleEvaluatorRegistries.defaultRegistry();
    private ExecutionEventSink executionEventSink = new NoopExecutionEventSink();
    private EngineRuntimeBridge runtimeBridge = EngineRuntimeBridge.noop();
    private boolean defaultRulesInitialized;
    private long assignmentRetryDelayMillis = 1000L;
    private long leaseWatchdogIntervalSeconds = 30L;
    private long workerCommandMaintenanceIntervalSeconds = 30L;
    private int workerCommandMaintenanceScanLimit = 1000;
    private int workerCommandDeliveryMaxAttempts = 3;
    private long runtimeReadyDispatchIntervalMillis = 250L;
    private long runtimeReadyDispatchIdleBackoffMaxMillis = 30_000L;
    private PollingIdleBackoffPolicy runtimeReadyDispatchIdleBackoffPolicy =
            ExponentialPollingIdleBackoffPolicy.INSTANCE;
    private long taskMessageLeaseSeconds = 300L;

    public EngineConfig() {
        InMemoryTaskShellStore defaultTaskShellStore = new InMemoryTaskShellStore();
        this.taskShellStore = defaultTaskShellStore;
    }

    public EngineConfig(EngineConfig source) {
        this.enabled = source.enabled;
        this.workerThreads = source.workerThreads;
        this.taskManager = null;
        this.taskCommandPort = null;
        this.taskEventService = null;
        this.taskQueryPort = null;
        this.taskResultIngestFacade = null;
        this.taskAssignmentRuntimePort = null;
        this.taskLeaseMaintenancePort = null;
        this.taskDispatchWakeupPort = null;
        this.taskShellLifecycleMaintenancePort = null;
        this.taskRuntimeRecoveryPort = null;
        this.taskRuntimeServingLane = null;
        this.taskReadViewPort = null;
        this.taskShellStore = source.taskShellStore;
        this.taskRuntimeBootstrapConfig = source.taskRuntimeBootstrapConfig;
        this.taskRuntimeHandle = null;
        this.workerDeliveryTargetResolverExplicitlyConfigured =
                source.workerDeliveryTargetResolverExplicitlyConfigured;
        this.workerDeliveryTargetResolver = source.workerDeliveryTargetResolverExplicitlyConfigured
                ? source.workerDeliveryTargetResolver
                : selectedWorkerId -> Optional.empty();
        this.workerDeclarationStore = source.workerDeclarationStore;
        this.workerRegistry = source.workerRegistry;
        this.workerScoreBandSlotRuntime = source.workerScoreBandSlotRuntime;
        this.workerManager = null;
        this.workerCommandLifecycleOwner = source.workerCommandLifecycleOwner;
        this.workerDispatchEligibilityRuntime = source.workerDispatchEligibilityRuntime;
        this.workerStateProjectionRuntime = source.workerStateProjectionRuntime;
        this.workerControlRuntime = null;
        this.taskStageEvidenceOwner = source.taskStageEvidenceOwner;
        this.taskStageEvidenceService = null;
        this.recordService = source.recordService;
        this.ruleStorage = source.ruleStorage;
        this.ruleEvaluatorRegistry = source.ruleEvaluatorRegistry;
        this.executionEventSink = source.executionEventSink;
        this.runtimeBridge = source.runtimeBridge;
        this.defaultRulesInitialized = source.defaultRulesInitialized;
        this.assignmentRetryDelayMillis = source.assignmentRetryDelayMillis;
        this.leaseWatchdogIntervalSeconds = source.leaseWatchdogIntervalSeconds;
        this.workerCommandMaintenanceIntervalSeconds = source.workerCommandMaintenanceIntervalSeconds;
        this.workerCommandMaintenanceScanLimit = source.workerCommandMaintenanceScanLimit;
        this.workerCommandDeliveryMaxAttempts = source.workerCommandDeliveryMaxAttempts;
        this.runtimeReadyDispatchIntervalMillis = source.runtimeReadyDispatchIntervalMillis;
        this.runtimeReadyDispatchIdleBackoffMaxMillis = source.runtimeReadyDispatchIdleBackoffMaxMillis;
        this.runtimeReadyDispatchIdleBackoffPolicy = source.runtimeReadyDispatchIdleBackoffPolicy;
        this.taskMessageLeaseSeconds = source.taskMessageLeaseSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public EngineRuntimeKernel createRuntimeKernel() {
        return new EngineRuntimeKernel(new KernelConfigView());
    }

    TaskCommandPort taskCommandPort() {
        ensureTaskRuntimeServingLane();
        return taskCommandPort;
    }

    TaskEventService taskEventService() {
        ensureTaskRuntimeServingLane();
        return taskEventService;
    }

    public TaskEventListenerRegistrar getTaskEventListeners() {
        return taskEventService();
    }

    public TaskAssignmentEventSink getTaskAssignmentEvents() {
        return taskEventService();
    }

    TaskQueryPort taskQueryPort() {
        ensureTaskRuntimeServingLane();
        return taskQueryPort;
    }

    public TaskResultIngestFacade getTaskResultIngestFacade() {
        if (taskResultIngestFacade == null) {
            taskResultIngestFacade = new TaskManagerResultIngestFacade(ensureTaskRuntimeServingLane());
        }
        return taskResultIngestFacade;
    }

    public TaskReadViewPort getTaskReadViewPort() {
        if (taskReadViewPort == null) {
            taskReadViewPort = new EngineTaskReadOperations(this);
        }
        return taskReadViewPort;
    }

    TaskReadViewProjectionStore taskReadViewProjection() {
        return taskReadViewProjection;
    }

    Optional<TaskScoreV1> taskRuntimeScore(String taskId, String laneKey) {
        TaskRuntimeHandle handle = taskRuntimeHandle;
        if (handle == null) {
            return Optional.empty();
        }
        return handle.runtime().taskScore(taskId, laneKey);
    }

    TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit) {
        var window = ensureTaskRuntimeServingLane().readTaskResults(taskId, afterSeq, limit);
        return new TaskResultWindowSnapshot(
                window.taskId(),
                window.rows().stream()
                        .map(EngineConfig::toResultItemSnapshot)
                        .toList(),
                window.nextAfterSeq(),
                window.hasMore(),
                window.totalVisible());
    }

    TaskWorkStatsSnapshot getTaskWorkStats(String taskId) {
        var stats = ensureTaskRuntimeServingLane().getTaskRuntimeProgressSnapshot(taskId);
        if (stats == null) {
            return TaskWorkStatsSnapshot.EMPTY;
        }
        return new TaskWorkStatsSnapshot(
                stats.totalCount(),
                stats.readyCount(),
                stats.activeCount(),
                stats.delayedCount(),
                stats.successCount(),
                stats.failedCount(),
                stats.expiredCount(),
                stats.finalCount());
    }

    TaskStateValidationResult validateTaskState(String taskId) {
        return taskQueryPort().validateTaskState(taskId);
    }

    TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskQueryPort().resolveTaskState(taskId);
    }

    List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId) {
        return ensureTaskRuntimeServingLane().getActiveLeaseCandidates(taskId).stream()
                .map(EngineConfig::toActiveLeaseSnapshot)
                .toList();
    }

    Optional<TaskWorkFinalSnapshot> getVisibleTaskResultByMessageId(String taskId, String messageId) {
        return ensureTaskRuntimeServingLane().getVisibleTaskResultByMessageId(taskId, messageId)
                .map(EngineConfig::toFinalSnapshot);
    }

    long countVisibleTaskResults(String taskId) {
        return ensureTaskRuntimeServingLane().countVisibleTaskResults(taskId);
    }

    public TaskAssignmentRuntimePort getTaskAssignmentRuntimePort() {
        if (taskAssignmentRuntimePort == null) {
            taskAssignmentRuntimePort = ensureTaskRuntimeServingLane();
        }
        return taskAssignmentRuntimePort;
    }

    public TaskLeaseMaintenancePort getTaskLeaseMaintenancePort() {
        if (taskLeaseMaintenancePort == null) {
            taskLeaseMaintenancePort = ensureTaskRuntimeServingLane();
        }
        return taskLeaseMaintenancePort;
    }

    public TaskDispatchWakeupPort getTaskDispatchWakeupPort() {
        if (taskDispatchWakeupPort == null) {
            taskDispatchWakeupPort = ensureTaskRuntimeServingLane();
        }
        return taskDispatchWakeupPort;
    }

    public TaskShellLifecycleMaintenancePort getTaskShellLifecycleMaintenancePort() {
        if (taskShellLifecycleMaintenancePort == null) {
            taskShellLifecycleMaintenancePort = ensureTaskManager();
        }
        return taskShellLifecycleMaintenancePort;
    }

    public TaskRuntimeRecoveryPort getTaskRuntimeRecoveryPort() {
        if (taskRuntimeRecoveryPort == null) {
            taskRuntimeRecoveryPort = ensureTaskRuntimeServingLane();
        }
        return taskRuntimeRecoveryPort;
    }

    public TraceEventLogger getTraceEventLogger() {
        if (traceEventLogger == null) {
            traceEventLogger = new TraceEventLogger(getExecutionEventSink());
        }
        return traceEventLogger;
    }

    public TaskRuntimeBootstrapConfig getTaskRuntimeBootstrapConfig() {
        return taskRuntimeBootstrapConfig;
    }

    public TaskRuntimeCommandPort getTaskRuntimeCommandPort() {
        return ensureTaskRuntimeHandle().commands();
    }

    public TaskCommandOutcome createTaskShellDescriptor(TaskShellCreateRequestDto dto, String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        validateTaskShellCreateRequest(dto);
        UserRef user = UserRef.of(dto.getUserId());
        Task task = new Task(
                normalizedTaskId,
                deriveTaskName(dto, normalizedTaskId),
                dto.getProject(),
                0,
                dto.getSharedConfig() != null ? dto.getSharedConfig() : new java.util.HashMap<>(),
                user
        );
        task.setTenantId(dto.getTenantId());
        task.setContract(dto.getContract());
        task.setExecutionSpec(TaskExecutionSpec.normalized(dto.getExecutionSpec()));
        task.setSourceRef(normalizeSourceRef(dto.getSourceRef()));
        task.setIntakeStatus(TaskIntakeStatus.OPEN);
        taskShellStore.saveTask(task);
        taskReadViewProjection.recordTaskSnapshot(task);
        return TaskCommandOutcome.applied(normalizedTaskId, "TASK_CREATED", "Task shell created");
    }

    public void setTaskRuntimeBootstrapConfig(TaskRuntimeBootstrapConfig taskRuntimeBootstrapConfig) {
        if (this.taskManager != null || this.taskRuntimeHandle != null || this.taskRuntimeServingLane != null) {
            throw new IllegalStateException(
                    "Cannot replace taskRuntimeBootstrapConfig after task-runtime assembly has been materialized");
        }
        this.taskRuntimeBootstrapConfig = taskRuntimeBootstrapConfig == null
                ? TaskRuntimeBootstrapConfig.memory()
                : taskRuntimeBootstrapConfig;
    }

    public void useMemoryTaskRuntime() {
        setTaskRuntimeBootstrapConfig(TaskRuntimeBootstrapConfig.memory());
    }

    public void useRedisTaskRuntime(String redisUri, String redisNamespace) {
        setTaskRuntimeBootstrapConfig(TaskRuntimeBootstrapConfig.redis(redisUri, redisNamespace));
    }

    TaskShellStore getTaskShellStore() {
        return taskShellStore;
    }

    public void setTaskShellStore(TaskShellStore taskShellStore) {
        if (taskShellStore == null) {
            throw new IllegalArgumentException("taskShellStore must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskShellStore after taskManager has been configured");
        }
        this.taskShellStore = taskShellStore;
    }

    private WorkerManager workerManager() {
        if (workerManager == null) {
            workerManager = new WorkerManager(
                    getWorkerDeclarationStore(),
                    this::lookupWorkerReachability,
                    getWorkerRegistry(),
                    getWorkerScoreBandSlotRuntime()
            );
        }
        return workerManager;
    }

    public WorkerResourceQueryRuntime getWorkerResourceQueryRuntime() {
        return workerManager();
    }

    public WorkerResourceDeclarationRuntime getWorkerResourceDeclarationRuntime() {
        return workerManager();
    }

    public WorkerNodeBindingRuntime getWorkerNodeBindingRuntime() {
        return workerManager();
    }

    public WorkerHeartbeatRuntime getWorkerHeartbeatRuntime() {
        return workerManager();
    }

    public WorkerSchedulingViewRuntime getWorkerSchedulingViewRuntime() {
        return workerManager();
    }

    public WorkerAdmissionRuntime getWorkerAdmissionRuntime() {
        return workerManager();
    }

    public WorkerSelectionRuntime getWorkerSelectionRuntime() {
        return workerManager();
    }

    public WorkerAvailabilityWakeupRuntime getWorkerAvailabilityWakeupRuntime() {
        return workerManager();
    }

    public WorkerDispatchGateRuntime getWorkerDispatchGateRuntime() {
        return workerManager();
    }

    public WorkerDispatchBlockRuntime getWorkerDispatchBlockRuntime() {
        return workerManager();
    }

    public WorkerDispatchRecoveryRuntime getWorkerDispatchRecoveryRuntime() {
        return workerManager();
    }

    public WorkerReportRuntime getWorkerReportRuntime() {
        return workerManager();
    }

    public WorkerControlRuntime getWorkerControlRuntime() {
        if (workerControlRuntime == null) {
            workerControlRuntime = new WorkerControlService(
                    getWorkerReportRuntime(),
                    getWorkerResourceQueryRuntime(),
                    getWorkerDispatchEligibilityRuntime(),
                    workerCommandLifecycleOwner,
                    workerStateProjectionRuntime,
                    getTraceEventLogger()
            );
        }
        return workerControlRuntime;
    }

    public TaskStageEvidenceService getTaskStageEvidenceService() {
        if (taskStageEvidenceService == null) {
            taskStageEvidenceService = new TaskStageEvidenceService(
                    taskStageEvidenceOwner,
                    getTraceEventLogger()
            );
        }
        return taskStageEvidenceService;
    }

    public WorkerReachabilityState getWorkerReachability(String workerId) {
        return lookupWorkerReachability(workerId);
    }

    public void setWorkerReachabilityLookup(Function<String, WorkerReachabilityState> workerReachabilityLookup) {
        this.workerReachabilityLookup = workerReachabilityLookup != null
                ? workerReachabilityLookup
                : UNKNOWN_WORKER_REACHABILITY;
    }

    private WorkerReachabilityState lookupWorkerReachability(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        WorkerReachabilityState state = workerReachabilityLookup.apply(workerId.trim());
        return state != null ? state : WorkerReachabilityState.UNKNOWN;
    }

    public Optional<SelectedWorkerDeliveryTargetEvidence> resolveWorkerDeliveryTarget(String selectedWorkerId) {
        return workerDeliveryTargetResolver.apply(selectedWorkerId);
    }

    public boolean isWorkerDeliveryTargetResolverExplicitlyConfigured() {
        return workerDeliveryTargetResolverExplicitlyConfigured;
    }

    public void setWorkerDeliveryTargetResolver(
            Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> workerDeliveryTargetResolver) {
        this.workerDeliveryTargetResolver = workerDeliveryTargetResolver != null
                ? workerDeliveryTargetResolver
                : selectedWorkerId -> Optional.empty();
        this.workerDeliveryTargetResolverExplicitlyConfigured = workerDeliveryTargetResolver != null;
    }

    public WorkerDispatchEligibilityRuntime getWorkerDispatchEligibilityRuntime() {
        if (workerDispatchEligibilityRuntime == null) {
            workerDispatchEligibilityRuntime = new DefaultWorkerDispatchAvailabilityPolicy(
                    getWorkerDispatchGateRuntime(),
                    workerManager()
            );
        }
        return workerDispatchEligibilityRuntime;
    }

    public void setWorkerDispatchEligibilityRuntime(WorkerDispatchEligibilityRuntime workerDispatchEligibilityRuntime) {
        this.workerDispatchEligibilityRuntime = workerDispatchEligibilityRuntime;
        this.workerControlRuntime = null;
    }

    public WorkerDeclarationStore getWorkerDeclarationStore() {
        return workerDeclarationStore;
    }

    public void setWorkerDeclarationStore(WorkerDeclarationStore workerDeclarationStore) {
        if (workerDeclarationStore == null) {
            throw new IllegalArgumentException("workerDeclarationStore must not be null");
        }
        this.workerDeclarationStore = workerDeclarationStore;
        this.workerManager = null;
        this.workerControlRuntime = null;
    }

    public WorkerRegistry getWorkerRegistry() {
        if (workerRegistry == null) {
            workerRegistry = new InMemoryWorkerRegistry();
        }
        return workerRegistry;
    }

    public void setWorkerRegistry(WorkerRegistry workerRegistry) {
        if (workerRegistry == null) {
            throw new IllegalArgumentException("workerRegistry must not be null");
        }
        if (this.workerManager != null) {
            throw new IllegalStateException("Cannot replace workerRegistry after workerManager has been configured");
        }
        this.workerRegistry = workerRegistry;
        this.workerControlRuntime = null;
    }

    public WorkerScoreBandSlotRuntime getWorkerScoreBandSlotRuntime() {
        if (workerScoreBandSlotRuntime == null) {
            workerScoreBandSlotRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        }
        return workerScoreBandSlotRuntime;
    }

    public void setWorkerScoreBandSlotRuntime(WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime) {
        if (workerScoreBandSlotRuntime == null) {
            throw new IllegalArgumentException("workerScoreBandSlotRuntime must not be null");
        }
        if (this.workerManager != null) {
            throw new IllegalStateException(
                    "Cannot replace workerScoreBandSlotRuntime after workerManager has been configured");
        }
        this.workerScoreBandSlotRuntime = workerScoreBandSlotRuntime;
        this.workerControlRuntime = null;
    }

    public AssignmentDiagnosticRecorder getRecordService() {
        return recordService;
    }

    public void setRecordService(AssignmentDiagnosticRecorder recordService) {
        this.recordService = recordService;
    }

    public MatchingRuleSetProvider getMatchingRuleSetProvider() {
        ensureDefaultRulesInitialized();
        return new StorageBackedMatchingRuleSetProvider(getRuleStorage());
    }

    public MatchingRuleEvaluator<Map<String, Object>> getMatchingRuleEvaluator() {
        return new RegistryBackedMatchingRuleEvaluator(getRuleEvaluatorRegistry());
    }

    public RuleStorage getRuleStorage() {
        return ruleStorage;
    }

    public void setRuleStorage(RuleStorage ruleStorage) {
        if (ruleStorage == null) {
            throw new IllegalArgumentException("ruleStorage must not be null");
        }
        this.ruleStorage = ruleStorage;
        this.defaultRulesInitialized = false;
    }

    public RuleEvaluatorRegistry<Map<String, Object>> getRuleEvaluatorRegistry() {
        return ruleEvaluatorRegistry;
    }

    public void setRuleEvaluatorRegistry(RuleEvaluatorRegistry<Map<String, Object>> ruleEvaluatorRegistry) {
        if (ruleEvaluatorRegistry == null) {
            throw new IllegalArgumentException("ruleEvaluatorRegistry must not be null");
        }
        this.ruleEvaluatorRegistry = ruleEvaluatorRegistry;
    }

    public ExecutionEventSink getExecutionEventSink() {
        return executionEventSink;
    }

    public void setExecutionEventSink(ExecutionEventSink executionEventSink) {
        if (executionEventSink == null) {
            throw new IllegalArgumentException("executionEventSink must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace executionEventSink after engine assembly has been materialized");
        }
        this.executionEventSink = executionEventSink;
        this.traceEventLogger = null;
        this.workerControlRuntime = null;
        this.taskStageEvidenceService = null;
    }

    public EngineRuntimeBridge getRuntimeBridge() {
        return runtimeBridge;
    }

    public void setRuntimeBridge(EngineRuntimeBridge runtimeBridge) {
        if (runtimeBridge == null) {
            throw new IllegalArgumentException("runtimeBridge must not be null");
        }
        this.runtimeBridge = runtimeBridge;
    }

    public long getAssignmentRetryDelayMillis() {
        return assignmentRetryDelayMillis;
    }

    public void setAssignmentRetryDelayMillis(long assignmentRetryDelayMillis) {
        this.assignmentRetryDelayMillis = assignmentRetryDelayMillis;
    }

    public long getLeaseWatchdogIntervalSeconds() {
        return leaseWatchdogIntervalSeconds;
    }

    public void setLeaseWatchdogIntervalSeconds(long leaseWatchdogIntervalSeconds) {
        this.leaseWatchdogIntervalSeconds = leaseWatchdogIntervalSeconds;
    }

    public long getWorkerCommandMaintenanceIntervalSeconds() {
        return workerCommandMaintenanceIntervalSeconds;
    }

    public void setWorkerCommandMaintenanceIntervalSeconds(long workerCommandMaintenanceIntervalSeconds) {
        this.workerCommandMaintenanceIntervalSeconds = workerCommandMaintenanceIntervalSeconds;
    }

    public int getWorkerCommandMaintenanceScanLimit() {
        return workerCommandMaintenanceScanLimit;
    }

    public void setWorkerCommandMaintenanceScanLimit(int workerCommandMaintenanceScanLimit) {
        this.workerCommandMaintenanceScanLimit = workerCommandMaintenanceScanLimit;
    }

    public int getWorkerCommandDeliveryMaxAttempts() {
        return workerCommandDeliveryMaxAttempts;
    }

    public void setWorkerCommandDeliveryMaxAttempts(int workerCommandDeliveryMaxAttempts) {
        this.workerCommandDeliveryMaxAttempts = workerCommandDeliveryMaxAttempts;
    }

    public long getRuntimeReadyDispatchIntervalMillis() {
        return runtimeReadyDispatchIntervalMillis;
    }

    public void setRuntimeReadyDispatchIntervalMillis(long runtimeReadyDispatchIntervalMillis) {
        this.runtimeReadyDispatchIntervalMillis = runtimeReadyDispatchIntervalMillis;
    }

    public long getRuntimeReadyDispatchIdleBackoffMaxMillis() {
        return runtimeReadyDispatchIdleBackoffMaxMillis;
    }

    public void setRuntimeReadyDispatchIdleBackoffMaxMillis(long runtimeReadyDispatchIdleBackoffMaxMillis) {
        this.runtimeReadyDispatchIdleBackoffMaxMillis = runtimeReadyDispatchIdleBackoffMaxMillis;
    }

    public PollingIdleBackoffPolicy getRuntimeReadyDispatchIdleBackoffPolicy() {
        return runtimeReadyDispatchIdleBackoffPolicy;
    }

    public void setRuntimeReadyDispatchIdleBackoffPolicy(
            PollingIdleBackoffPolicy runtimeReadyDispatchIdleBackoffPolicy) {
        this.runtimeReadyDispatchIdleBackoffPolicy = runtimeReadyDispatchIdleBackoffPolicy != null
                ? runtimeReadyDispatchIdleBackoffPolicy
                : ExponentialPollingIdleBackoffPolicy.INSTANCE;
    }

    public long getTaskMessageLeaseSeconds() {
        return taskMessageLeaseSeconds;
    }

    public void setTaskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
        if (taskRuntimeServingLane != null && this.taskMessageLeaseSeconds != taskMessageLeaseSeconds) {
            throw new IllegalStateException(
                    "Cannot replace taskMessageLeaseSeconds after task-runtime serving lane has been materialized");
        }
        this.taskMessageLeaseSeconds = taskMessageLeaseSeconds;
        if (taskManager != null) {
            taskManager.setWorkLeaseSeconds(taskMessageLeaseSeconds);
        }
    }

    public void shutdownTaskRuntime() {
        if (taskManager != null) {
            taskManager.shutdown();
            taskManager = null;
        }
        taskCommandPort = null;
        taskEventService = null;
        taskQueryPort = null;
        taskResultIngestFacade = null;
        taskAssignmentRuntimePort = null;
        taskLeaseMaintenancePort = null;
        taskDispatchWakeupPort = null;
        taskShellLifecycleMaintenancePort = null;
        taskRuntimeRecoveryPort = null;
        taskRuntimeServingLane = null;
        taskReadProjectionListenersInstalled = false;
        TaskRuntimeHandle handle = taskRuntimeHandle;
        taskRuntimeHandle = null;
        if (handle != null) {
            handle.close();
        }
        if (workerRegistry instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close workerRegistry", e);
            }
        }
        if (workerScoreBandSlotRuntime instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close workerScoreBandSlotRuntime", e);
            }
        }
    }

    public void registerStarterOwnedTaskRuntimeLoops(List<TaskRuntimeLoop> loops) {
        ensureTaskRuntimeHandle().registerLoops(loops);
    }

    public void stopStarterOwnedTaskRuntimeLoops() {
        if (taskRuntimeHandle != null) {
            taskRuntimeHandle.stop();
        }
    }

    private final class KernelConfigView implements EngineRuntimeKernelConfig {

        @Override
        public TaskCommandPort getTaskCommandPort() {
            return taskCommandPort();
        }

        @Override
        public TaskRuntimeRecoveryPort getTaskRuntimeRecoveryPort() {
            return EngineConfig.this.getTaskRuntimeRecoveryPort();
        }

        @Override
        public TaskLeaseMaintenancePort getTaskLeaseMaintenancePort() {
            return EngineConfig.this.getTaskLeaseMaintenancePort();
        }

        @Override
        public TaskDispatchWakeupPort getTaskDispatchWakeupPort() {
            return EngineConfig.this.getTaskDispatchWakeupPort();
        }

        @Override
        public TaskShellLifecycleMaintenancePort getTaskShellLifecycleMaintenancePort() {
            return EngineConfig.this.getTaskShellLifecycleMaintenancePort();
        }

        @Override
        public TaskAssignmentRuntimePort getTaskAssignmentRuntimePort() {
            return EngineConfig.this.getTaskAssignmentRuntimePort();
        }

        @Override
        public TaskEventService getTaskEventService() {
            return taskEventService();
        }

        @Override
        public WorkerAvailabilityWakeupRuntime getWorkerAvailabilityWakeupRuntime() {
            return EngineConfig.this.getWorkerAvailabilityWakeupRuntime();
        }

        @Override
        public WorkerSelectionRuntime getWorkerSelectionRuntime() {
            return EngineConfig.this.getWorkerSelectionRuntime();
        }

        @Override
        public WorkerControlRuntime getWorkerControlRuntime() {
            return EngineConfig.this.getWorkerControlRuntime();
        }

        @Override
        public AssignmentDiagnosticRecorder getRecordService() {
            return EngineConfig.this.getRecordService();
        }

        @Override
        public TraceEventLogger getTraceEventLogger() {
            return EngineConfig.this.getTraceEventLogger();
        }

        @Override
        public long getTaskMessageLeaseSeconds() {
            return EngineConfig.this.getTaskMessageLeaseSeconds();
        }

        @Override
        public long getAssignmentRetryDelayMillis() {
            return EngineConfig.this.getAssignmentRetryDelayMillis();
        }

        @Override
        public long getRuntimeReadyDispatchIntervalMillis() {
            return EngineConfig.this.getRuntimeReadyDispatchIntervalMillis();
        }

        @Override
        public long getRuntimeReadyDispatchIdleBackoffMaxMillis() {
            return EngineConfig.this.getRuntimeReadyDispatchIdleBackoffMaxMillis();
        }

        @Override
        public PollingIdleBackoffPolicy getRuntimeReadyDispatchIdleBackoffPolicy() {
            return EngineConfig.this.getRuntimeReadyDispatchIdleBackoffPolicy();
        }

        @Override
        public long getLeaseWatchdogIntervalSeconds() {
            return EngineConfig.this.getLeaseWatchdogIntervalSeconds();
        }

        @Override
        public long getWorkerCommandMaintenanceIntervalSeconds() {
            return EngineConfig.this.getWorkerCommandMaintenanceIntervalSeconds();
        }

        @Override
        public int getWorkerCommandMaintenanceScanLimit() {
            return EngineConfig.this.getWorkerCommandMaintenanceScanLimit();
        }

        @Override
        public int getWorkerCommandDeliveryMaxAttempts() {
            return EngineConfig.this.getWorkerCommandDeliveryMaxAttempts();
        }
    }

    private void validateTaskShellCreateRequest(TaskShellCreateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        ProjectRef.require(dto.getProject());
        UserRef.requireUserId(dto.getUserId());
        if (dto.getTenantId() == null || dto.getTenantId().isBlank()) {
            dto.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
        }
        TaskExecutionSpec normalizedSpec = TaskExecutionSpec.normalized(dto.getExecutionSpec());
        TaskContract contract = dto.getContract() == null
                ? TaskPolicyPresetDefinition.defaultContract(null)
                : dto.getContract();
        normalizedSpec.setWorkloadClass(resolveWorkloadClass(contract, normalizedSpec));
        dto.setContract(contract);
        dto.setExecutionSpec(normalizedSpec);
    }

    private com.xa.mass.base.enums.task.TaskWorkloadClass resolveWorkloadClass(TaskContract contract,
                                                                               TaskExecutionSpec normalizedSpec) {
        if (normalizedSpec != null && normalizedSpec.getWorkloadClass() != null) {
            return normalizedSpec.getWorkloadClass();
        }
        return TaskPolicyPresetDefinition.forContract(contract).defaultRuntimeProfile().workloadClass();
    }

    private String deriveTaskName(TaskShellCreateRequestDto dto, String taskId) {
        String project = dto.getProject() != null ? dto.getProject().trim() : "task";
        TaskContract contract = TaskPolicyPresetDefinition.defaultContract(dto.getContract());
        String normalizedContract = contract.name().toLowerCase(java.util.Locale.ROOT);
        String profile = dto.getExecutionSpec() != null && dto.getExecutionSpec().getProfile() != null
                ? dto.getExecutionSpec().getProfile().name().toLowerCase(java.util.Locale.ROOT)
                : "standard";
        String sourceRef = normalizeSourceRef(dto.getSourceRef());
        String sourceHint = sourceRef == null ? null : basename(sourceRef);
        String shortTaskId = taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
        if (sourceHint != null && !sourceHint.isBlank()) {
            return project + "-" + normalizedContract + "-" + profile + "-" + sourceHint + "-" + shortTaskId;
        }
        return project + "-" + normalizedContract + "-" + profile + "-" + shortTaskId;
    }

    private String basename(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String sanitized = leaf.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? null : sanitized;
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        return sourceRef.trim();
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return taskId.trim();
    }

    private TaskManager ensureTaskManager() {
        if (taskManager == null) {
            taskManager = new TaskManager(
                    getTaskShellRuntimeStore(),
                    getTaskShellRuntimeLifecycleQuery(),
                    new ContractAwareTaskTerminalPolicy(),
                    getExecutionEventSink()
            );
        }
        taskManager.setWorkLeaseSeconds(taskMessageLeaseSeconds);
        return taskManager;
    }

    private TaskRuntimeServingLane ensureTaskRuntimeServingLane() {
        if (taskRuntimeServingLane == null) {
            TaskManager manager = ensureTaskManager();
            if (taskCommandPort == null) {
                taskCommandPort = new TaskReadViewPublishingTaskCommandPort(manager, taskReadViewProjection);
            }
            if (taskQueryPort == null) {
                taskQueryPort = manager;
            }
            if (taskEventService == null) {
                taskEventService = new TaskEventService(manager);
                installTaskReadProjectionListeners(taskEventService);
            }
            var taskRuntimeHandle = ensureTaskRuntimeHandle();
            TaskRuntimePortSet taskRuntime = taskRuntimeHandle.runtime();
            taskRuntimeServingLane = manager.createTaskRuntimeServingLane(
                    taskRuntime,
                    taskRuntime,
                    taskRuntime,
                    taskRuntime,
                    requireResultWindowReadModel(taskRuntime),
                    new ContractAwareTaskTerminalPolicy(),
                    null,
                    getTraceEventLogger(),
                    taskMessageLeaseSeconds,
                    TaskManager.MAX_INGEST_BATCH_ITEMS,
                    86_400_000L);
            manager.installTaskRuntimeServingLane(taskRuntimeServingLane);
        }
        return taskRuntimeServingLane;
    }

    private void installTaskReadProjectionListeners(TaskEventListenerRegistrar eventListeners) {
        if (taskReadProjectionListenersInstalled) {
            return;
        }
        java.util.function.Consumer<Task> projectionListener = taskReadViewProjection::recordTaskSnapshot;
        eventListeners.addTaskCreatedListener(projectionListener);
        eventListeners.addTaskReadyListener(projectionListener);
        eventListeners.addTaskDispatchListener(projectionListener);
        eventListeners.addTaskAssignedListener(projectionListener);
        eventListeners.addTaskTerminalListener(projectionListener);
        taskReadProjectionListenersInstalled = true;
    }

    private TaskRuntimeResultWindowReadModel requireResultWindowReadModel(TaskRuntimePortSet taskRuntime) {
        if (taskRuntime instanceof TaskRuntimeResultWindowReadModel resultWindowReadModel) {
            return resultWindowReadModel;
        }
        throw new IllegalStateException("Task runtime does not expose final-result window read model");
    }

    private TaskRuntimeHandle ensureTaskRuntimeHandle() {
        if (taskRuntimeHandle == null) {
            taskRuntimeHandle = TaskRuntimeStarter.start(taskRuntimeBootstrapConfig, List.of());
        }
        return taskRuntimeHandle;
    }

    private static TaskActiveLeaseSnapshot toActiveLeaseSnapshot(ActiveLeaseRepairCandidate lease) {
        return new TaskActiveLeaseSnapshot(
                lease.taskId(),
                lease.messageId(),
                lease.workerId(),
                lease.batchId(),
                null,
                Math.max(0, lease.attemptNo() - 1),
                Instant.ofEpochMilli(lease.leaseExpireAtMillis()),
                null);
    }

    private static TaskResultItemSnapshot toResultItemSnapshot(FinalResultRow row) {
        Instant completedAt = Instant.ofEpochMilli(row.finalizedAtMillis());
        return new TaskResultItemSnapshot(
                row.seq(),
                row.messageId(),
                null,
                resultStatus(row),
                resultFinalReason(row),
                Math.max(0, row.attemptNo() - 1),
                Math.max(0, row.attemptNo() - 1),
                row.workerId(),
                row.batchId(),
                attemptId(row),
                null,
                completedAt,
                null,
                null,
                completedAt,
                completedAt,
                null,
                row.failureReason(),
                row.resultPayloadJson());
    }

    private static TaskWorkFinalSnapshot toFinalSnapshot(FinalResultRow row) {
        Instant completedAt = Instant.ofEpochMilli(row.finalizedAtMillis());
        return new TaskWorkFinalSnapshot(
                row.taskId(),
                row.messageId(),
                resultStatus(row),
                resultFinalReason(row),
                Math.max(0, row.attemptNo() - 1),
                Math.max(0, row.attemptNo() - 1),
                null,
                row.workerId(),
                row.batchId(),
                attemptId(row),
                null,
                row.failureReason(),
                null,
                completedAt,
                null,
                null,
                completedAt,
                completedAt,
                row.resultPayloadJson());
    }

    private static String attemptId(FinalResultRow row) {
        return TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                row.messageId(),
                row.attemptNo(),
                row.workerId(),
                row.batchId());
    }

    private static String resultStatus(FinalResultRow row) {
        if (row.success()) {
            return "SUCCESS";
        }
        return row.source() == ResultApplySource.LEASE_TIMEOUT ? "EXPIRED" : "FAILED";
    }

    private static String resultFinalReason(FinalResultRow row) {
        if (row.success()) {
            return "BUSINESS_SUCCESS";
        }
        return row.source() == ResultApplySource.LEASE_TIMEOUT ? "LEASE_EXPIRED" : "BUSINESS_FAILED";
    }

    private void ensureDefaultRulesInitialized() {
        if (defaultRulesInitialized) {
            return;
        }
        getRuleStorage().addRules(RuleConfig.getDefaultWorkerMatchRules());
        defaultRulesInitialized = true;
    }

    private TaskShellRuntimeStore getTaskShellRuntimeStore() {
        if (taskShellStore instanceof TaskShellRuntimeStore runtimeStore) {
            return runtimeStore;
        }
        throw new IllegalStateException("taskShellStore must implement TaskShellRuntimeStore");
    }

    private TaskShellRuntimeLifecycleQuery getTaskShellRuntimeLifecycleQuery() {
        if (taskShellStore instanceof TaskShellRuntimeLifecycleQuery lifecycleQuery) {
            return lifecycleQuery;
        }
        throw new IllegalStateException("taskShellStore must implement TaskShellRuntimeLifecycleQuery");
    }

}
