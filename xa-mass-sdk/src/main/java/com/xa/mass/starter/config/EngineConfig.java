package com.xa.mass.starter.config;

import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.engine.TaskShellLifecycleMaintenancePort;
import com.xa.mass.engine.WorkerControlRuntime;
import com.xa.mass.engine.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.engine.control.WorkerDispatchAvailabilityPolicy;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityView;
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.stage.TaskStageEvidenceOwner;
import com.xa.mass.engine.stage.TaskStageEvidenceService;
import com.xa.mass.engine.rules.MatchingRuleEvaluator;
import com.xa.mass.engine.rules.MatchingRuleSetProvider;
import com.xa.mass.engine.rules.RegistryBackedMatchingRuleEvaluator;
import com.xa.mass.engine.rules.RuleConfig;
import com.xa.mass.engine.rules.RuleEvaluatorRegistry;
import com.xa.mass.engine.rules.RuleEvaluatorRegistries;
import com.xa.mass.engine.rules.StorageBackedMatchingRuleSetProvider;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.watchdog.ExponentialPollingIdleBackoffPolicy;
import com.xa.mass.engine.watchdog.PollingIdleBackoffPolicy;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.worker.RandomWorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicy;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.report.WorkerReportRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerWarmHintRuntime;
import com.xa.mass.worker.runtime.routing.WorkerRouteBucketPolicies;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.api.WorkerDeclarationStore;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
import com.xa.mass.starter.EngineRuntimeBridge;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.NoopExecutionEventSink;

import java.util.Map;

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

    private boolean enabled = true;
    private int workerThreads = 8;

    private TaskManager taskManager;
    private TaskCommandService taskCommandService;
    private TaskEventService taskEventService;
    private TaskQueryService taskQueryService;
    private TaskResultIngestFacade taskResultIngestFacade;
    private TaskAssignmentRuntimePort taskAssignmentRuntimePort;
    private TaskLeaseMaintenancePort taskLeaseMaintenancePort;
    private TaskDispatchWakeupPort taskDispatchWakeupPort;
    private TaskShellLifecycleMaintenancePort taskShellLifecycleMaintenancePort;
    private TaskRuntimeRecoveryPort taskRuntimeRecoveryPort;
    private com.xa.mass.engine.util.TraceEventLogger traceEventLogger;
    private TaskShellStore taskShellStore;
    private TaskDetailStore taskDetailStore;
    private TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
    private TaskResultRuntime taskResultRuntime = new InMemoryTaskResultRuntime();
    private TaskWorkerMatchingStrategy matchingStrategy;
    private WorkerReachabilityView workerReachabilityView = WorkerReachabilityView.permissive();
    private WorkerDeclarationStore workerDeclarationStore = new InMemoryWorkerDeclarationStore();
    private WorkerRegistry workerRegistry;
    private final WorkerRouteBucketPolicy workerRouteBucketPolicy = WorkerRouteBucketPolicies.defaultPolicy();
    private WorkerManager workerManager;
    private WorkerCommandLifecycleOwner workerCommandLifecycleOwner = new WorkerCommandLifecycleOwner();
    private WorkerDispatchAvailabilityPolicy workerDispatchAvailabilityPolicy =
            new DefaultWorkerDispatchAvailabilityPolicy();
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
    private MassBootstrapDataProvider bootstrapDataProvider;
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
        this.taskDetailStore = defaultTaskShellStore;
    }

    public EngineConfig(EngineConfig source) {
        this.enabled = source.enabled;
        this.workerThreads = source.workerThreads;
        this.taskManager = source.taskManager;
        this.taskCommandService = source.taskCommandService;
        this.taskEventService = source.taskEventService;
        this.taskQueryService = source.taskQueryService;
        this.taskResultIngestFacade = source.taskResultIngestFacade;
        this.taskAssignmentRuntimePort = source.taskAssignmentRuntimePort;
        this.taskLeaseMaintenancePort = source.taskLeaseMaintenancePort;
        this.taskDispatchWakeupPort = source.taskDispatchWakeupPort;
        this.taskShellLifecycleMaintenancePort = source.taskShellLifecycleMaintenancePort;
        this.taskRuntimeRecoveryPort = source.taskRuntimeRecoveryPort;
        this.taskShellStore = source.taskShellStore;
        this.taskDetailStore = source.taskDetailStore;
        this.taskWorkRuntime = source.taskWorkRuntime;
        this.taskResultRuntime = source.taskResultRuntime;
        this.matchingStrategy = source.matchingStrategy;
        this.workerReachabilityView = source.workerReachabilityView;
        this.workerDeclarationStore = source.workerDeclarationStore;
        this.workerRegistry = source.workerRegistry;
        this.workerManager = null;
        this.workerCommandLifecycleOwner = source.workerCommandLifecycleOwner;
        this.workerDispatchAvailabilityPolicy = source.workerDispatchAvailabilityPolicy;
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
        this.bootstrapDataProvider = source.bootstrapDataProvider;
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

    public TaskWorkerMatchingStrategy getMatchingStrategy() {
        return matchingStrategy;
    }

    public void setMatchingStrategy(TaskWorkerMatchingStrategy matchingStrategy) {
        this.matchingStrategy = matchingStrategy;
    }

    public TaskCommandService getTaskCommandService() {
        if (taskCommandService == null) {
            taskCommandService = new TaskCommandService(ensureTaskManager());
        }
        return taskCommandService;
    }

    public TaskEventService getTaskEventService() {
        if (taskEventService == null) {
            taskEventService = new TaskEventService(ensureTaskManager());
        }
        return taskEventService;
    }

    public TaskQueryService getTaskQueryService() {
        if (taskQueryService == null) {
            taskQueryService = new TaskQueryService(ensureTaskManager());
        }
        return taskQueryService;
    }

    public TaskResultIngestFacade getTaskResultIngestFacade() {
        if (taskResultIngestFacade == null) {
            taskResultIngestFacade = new TaskManagerResultIngestFacade(ensureTaskManager());
        }
        return taskResultIngestFacade;
    }

    public TaskAssignmentRuntimePort getTaskAssignmentRuntimePort() {
        if (taskAssignmentRuntimePort == null) {
            taskAssignmentRuntimePort = ensureTaskManager();
        }
        return taskAssignmentRuntimePort;
    }

    public TaskLeaseMaintenancePort getTaskLeaseMaintenancePort() {
        if (taskLeaseMaintenancePort == null) {
            taskLeaseMaintenancePort = ensureTaskManager();
        }
        return taskLeaseMaintenancePort;
    }

    public TaskDispatchWakeupPort getTaskDispatchWakeupPort() {
        if (taskDispatchWakeupPort == null) {
            taskDispatchWakeupPort = ensureTaskManager();
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
            taskRuntimeRecoveryPort = ensureTaskManager();
        }
        return taskRuntimeRecoveryPort;
    }

    public com.xa.mass.engine.util.TraceEventLogger getTraceEventLogger() {
        if (traceEventLogger == null) {
            traceEventLogger = new com.xa.mass.engine.util.TraceEventLogger(getExecutionEventSink());
        }
        return traceEventLogger;
    }

    public TaskWorkRuntime getTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    public void setTaskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
        if (taskWorkRuntime == null) {
            throw new IllegalArgumentException("taskWorkRuntime must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskWorkRuntime after engine assembly has been materialized");
        }
        this.taskWorkRuntime = taskWorkRuntime;
    }

    public TaskResultRuntime getTaskResultRuntime() {
        return taskResultRuntime;
    }

    public void setTaskResultRuntime(TaskResultRuntime taskResultRuntime) {
        if (taskResultRuntime == null) {
            throw new IllegalArgumentException("taskResultRuntime must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskResultRuntime after engine assembly has been materialized");
        }
        this.taskResultRuntime = taskResultRuntime;
    }

    public TaskShellStore getTaskShellStore() {
        return taskShellStore;
    }

    public void setTaskShellStore(TaskShellStore taskShellStore) {
        if (taskShellStore == null) {
            throw new IllegalArgumentException("taskShellStore must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskShellStore after taskManager has been configured");
        }
        if (this.taskDetailStore == this.taskShellStore) {
            this.taskDetailStore = null;
        }
        this.taskShellStore = taskShellStore;
    }

    public TaskDetailStore getTaskDetailStore() {
        if (taskDetailStore != null) {
            return taskDetailStore;
        }
        throw new IllegalStateException(
                "taskDetailStore is not configured; provide an explicit taskDetailStore via setTaskDetailStore()");
    }

    public void setTaskDetailStore(TaskDetailStore taskDetailStore) {
        if (taskDetailStore == null) {
            throw new IllegalArgumentException("taskDetailStore must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskDetailStore after taskManager has been configured");
        }
        this.taskDetailStore = taskDetailStore;
    }

    private WorkerManager workerManager() {
        if (workerManager == null) {
            workerManager = new WorkerManager(
                    getWorkerDeclarationStore(),
                    workerReachabilityView,
                    getWorkerRegistry(),
                    workerRouteBucketPolicy
            );
        }
        return workerManager;
    }

    public WorkerResourceRuntime getWorkerResourceRuntime() {
        return workerManager();
    }

    public WorkerCandidateRuntime getWorkerCandidateRuntime() {
        return workerManager();
    }

    public WorkerSchedulingViewRuntime getWorkerSchedulingViewRuntime() {
        return workerManager();
    }

    public WorkerAdmissionRuntime getWorkerAdmissionRuntime() {
        return workerManager();
    }

    public WorkerAvailabilityWakeupRuntime getWorkerAvailabilityWakeupRuntime() {
        return workerManager();
    }

    public WorkerDispatchGateRuntime getWorkerDispatchGateRuntime() {
        return workerManager();
    }

    public WorkerReportRuntime getWorkerReportRuntime() {
        return workerManager();
    }

    public WorkerWarmHintRuntime getWorkerWarmHintRuntime() {
        return workerManager();
    }

    public WorkerControlRuntime getWorkerControlRuntime() {
        if (workerControlRuntime == null) {
            workerControlRuntime = new WorkerControlService(
                    getWorkerReportRuntime(),
                    getWorkerResourceRuntime(),
                    getWorkerDispatchGateRuntime(),
                    workerCommandLifecycleOwner,
                    workerStateProjectionRuntime,
                    workerDispatchAvailabilityPolicy,
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

    public WorkerReachabilityView getWorkerReachabilityView() {
        return workerReachabilityView;
    }

    public void setWorkerReachabilityView(WorkerReachabilityView workerReachabilityView) {
        this.workerReachabilityView = workerReachabilityView != null
                ? workerReachabilityView
                : WorkerReachabilityView.permissive();
        this.workerManager = null;
        this.workerControlRuntime = null;
    }

    public WorkerDispatchAvailabilityPolicy getWorkerDispatchAvailabilityPolicy() {
        return workerDispatchAvailabilityPolicy;
    }

    public void setWorkerDispatchAvailabilityPolicy(WorkerDispatchAvailabilityPolicy workerDispatchAvailabilityPolicy) {
        this.workerDispatchAvailabilityPolicy = workerDispatchAvailabilityPolicy != null
                ? workerDispatchAvailabilityPolicy
                : new DefaultWorkerDispatchAvailabilityPolicy();
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
            workerRegistry = new InMemoryWorkerRegistry(
                    workerRouteBucketPolicy,
                    RandomWorkerCandidateSamplingPolicy.defaultPolicy()
            );
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

    public MassBootstrapDataProvider getBootstrapDataProvider() {
        return bootstrapDataProvider;
    }

    public void setBootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
        this.bootstrapDataProvider = bootstrapDataProvider;
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
        this.taskMessageLeaseSeconds = taskMessageLeaseSeconds;
        if (taskManager != null) {
            taskManager.setWorkLeaseSeconds(taskMessageLeaseSeconds);
        }
    }

    public void shutdownTaskRuntime() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
        if (workerRegistry instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close workerRegistry", e);
            }
        }
    }

    private TaskManager ensureTaskManager() {
        if (taskManager == null) {
            taskManager = new TaskManager(
                    getTaskShellStore(),
                    getTaskDetailStore(),
                    getTaskWorkRuntime(),
                    getTaskResultRuntime(),
                    getExecutionEventSink()
            );
        }
        taskManager.setWorkLeaseSeconds(taskMessageLeaseSeconds);
        return taskManager;
    }

    private void ensureDefaultRulesInitialized() {
        if (defaultRulesInitialized) {
            return;
        }
        getRuleStorage().addRules(RuleConfig.getDefaultWorkerMatchRules());
        defaultRulesInitialized = true;
    }
}
