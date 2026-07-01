package com.xa.mass.starter;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.engine.EngineRuntimeLoop;
import com.xa.mass.engine.EngineRuntimeKernel;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.WorkerControlRuntime;
import com.xa.mass.engine.model.TaskAppendReceipt;
import com.xa.mass.engine.model.TaskDefinitionPatch;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.stage.TaskStageEvidenceResult;
import com.xa.mass.engine.stage.TaskStageEvidenceService;
import com.xa.mass.engine.stage.TaskStageProjection;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.task.runtime.starter.TaskRuntimeLoop;
import com.xa.mass.worker.runtime.command.WorkerCommandAcknowledgement;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.command.WorkerCommandRecord;
import com.xa.mass.worker.runtime.command.WorkerCommandRequest;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionResult;
import com.xa.mass.worker.runtime.report.WorkerStateReport;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Assembles and starts the task-scheduling engine for an embedded runtime.
 *
 * <h3>Event Model</h3>
 * <ul>
 *   <li><b>In-process (synchronous):</b> {@code TaskEventService}
 *       exposes the runtime listener surface. Its listeners fire inline on the
 *       calling thread and are used by the engine internals
 *       (assignment, resource release, etc.).</li>
 *   <li><b>Optional shell bridge:</b> process-local bridge wiring such as
 *       runtime EventBus forwarding is configured outside the kernel through
 *       {@link EngineRuntimeBridge}. It is not part of the default engine
 *       runtime truth.</li>
 * </ul>
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);
    private final EngineConfig config;
    private boolean running = false;

    private TaskCommandService taskCommands;
    private EngineRuntimeKernel runtimeKernel;
    private EngineRuntimeBridge runtimeBridge;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    public void start() {
        start(null);
    }

    public void start(TaskDispatchBatchListener dispatchBatchListener) {
        if (!config.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        if (running) {
            logger.info("MassEngine is already running, skipping duplicate start");
            return;
        }
        logger.info("Starting MassEngine with {} worker threads", config.getWorkerThreads());
        try {
            runtimeBridge = config.getRuntimeBridge();
            runtimeKernel = new EngineRuntimeKernel(config);
            EngineRuntimeKernel.StartedRuntime startedRuntime = runtimeKernel.start(dispatchBatchListener);
            config.registerStarterOwnedTaskRuntimeLoops(toTaskRuntimeLoops(startedRuntime.taskRuntimeLoops()));
            taskCommands = runtimeKernel.taskCommands();
            runtimeBridge.start(
                    startedRuntime.eventListeners(),
                    startedRuntime.dispatchWakeupCallback());
            running = true;
            logger.info("MassEngine started successfully");
        } catch (Exception e) {
            config.stopStarterOwnedTaskRuntimeLoops();
            if (runtimeKernel != null) {
                runtimeKernel.stop();
            }
            config.shutdownTaskRuntime();
            logger.error("Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }

    public void stop() {
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }
        logger.info("Stopping MassEngine...");
        try {
            if (runtimeBridge != null) {
                runtimeBridge.stop();
                runtimeBridge = null;
            }
            config.stopStarterOwnedTaskRuntimeLoops();
            if (runtimeKernel != null) {
                runtimeKernel.stop();
                runtimeKernel = null;
            }
            config.shutdownTaskRuntime();
            taskCommands = null;
            running = false;
            logger.info("MassEngine stopped successfully");
        } catch (Exception e) {
            logger.error("Error stopping MassEngine", e);
        }
    }

    public Task createTaskShell(TaskShellCreateRequestDto dto) {
        return requireStartedTaskCommands().createTaskShell(dto);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    private static List<TaskRuntimeLoop> toTaskRuntimeLoops(List<EngineRuntimeLoop> engineLoops) {
        if (engineLoops == null || engineLoops.isEmpty()) {
            return List.of();
        }
        return engineLoops.stream()
                .map(MassEngine::toTaskRuntimeLoop)
                .toList();
    }

    private static TaskRuntimeLoop toTaskRuntimeLoop(EngineRuntimeLoop engineLoop) {
        return new TaskRuntimeLoop() {
            @Override
            public void runOnce(com.xa.mass.task.runtime.starter.TaskRuntimeLoopContext context) {
                engineLoop.runOnce();
            }

            @Override
            public String name() {
                return engineLoop.name();
            }

            @Override
            public long intervalMillis() {
                return engineLoop.intervalMillis();
            }
        };
    }

    public void setWorkerReachabilityLookup(Function<String, WorkerReachabilityState> workerReachabilityLookup) {
        config.setWorkerReachabilityLookup(workerReachabilityLookup);
    }

    public WorkerReachabilityState workerReachability(String workerId) {
        return config.getWorkerReachability(workerId);
    }

    public boolean isWorkerDeliveryTargetResolverExplicitlyConfigured() {
        return config.isWorkerDeliveryTargetResolverExplicitlyConfigured();
    }

    public Optional<SelectedWorkerDeliveryTargetEvidence> resolveWorkerDeliveryTarget(String selectedWorkerId) {
        return config.resolveWorkerDeliveryTarget(selectedWorkerId);
    }

    public TaskResultIngestFacade taskResultIngestFacade() {
        return config.getTaskResultIngestFacade();
    }

    public void blockWorkerDispatch(String deliveryBucketId,
                                    String workerId,
                                    WorkerDispatchBlockSignal signal) {
        config.getWorkerDispatchBlockRuntime().blockWorkerDispatch(deliveryBucketId, workerId, signal);
    }

    public WorkerHeartbeatRuntime workerHeartbeatRuntime() {
        return config.getWorkerHeartbeatRuntime();
    }

    public Optional<WorkerResourceRecord> worker(String workerId) {
        return config.getWorkerResourceQueryRuntime().worker(workerId);
    }

    public List<WorkerResourceRecord> workers() {
        return config.getWorkerResourceQueryRuntime().workers();
    }

    public List<WorkerGroupRecord> workerGroups() {
        return config.getWorkerResourceQueryRuntime().workerGroups();
    }

    public List<AdapterNodeRecord> adapterNodes() {
        return config.getWorkerResourceQueryRuntime().adapterNodes();
    }

    public List<NodeGroupBindingRecord> nodeGroupBindings() {
        return config.getWorkerResourceQueryRuntime().nodeGroupBindings();
    }

    public void registerAdapterNode(AdapterNodeRecord record) {
        config.getWorkerResourceDeclarationRuntime().registerAdapterNode(record);
    }

    public void bindNodeGroup(NodeGroupBindingRecord record) {
        config.getWorkerNodeBindingRuntime().bindNodeGroup(record);
    }

    public void upsertWorkerGroup(WorkerGroupRecord record) {
        config.getWorkerResourceDeclarationRuntime().upsertWorkerGroup(record);
    }

    public void addWorker(WorkerDeclarationRecord record) {
        config.getWorkerResourceDeclarationRuntime().addWorker(record);
    }

    public Task getTask(String taskId) {
        return requireStartedTaskQueries().getTask(taskId);
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return config.getTaskShellStore().listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return config.getTaskShellStore().getTasksByStatus(status);
    }

    public TaskAppendReceipt appendTaskItemsWithReceipt(String taskId, List<Map<String, Object>> items) {
        return requireStartedTaskCommands().appendTaskItemsWithReceipt(taskId, items);
    }

    public boolean patchTaskDefinition(String taskId, TaskDefinitionPatch patch) {
        return requireStartedTaskCommands().patchTaskDefinition(taskId, patch);
    }

    public boolean approveTask(String taskId) {
        return requireStartedTaskCommands().approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return requireStartedTaskCommands().rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return requireStartedTaskCommands().blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return requireStartedTaskCommands().pauseTask(taskId);
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return requireStartedTaskCommands().resumeTaskDetailed(taskId);
    }

    public boolean cancelTask(String taskId) {
        return requireStartedTaskCommands().cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return requireStartedTaskCommands().terminateTask(taskId, reason);
    }

    public boolean sealTask(String taskId) {
        return requireStartedTaskCommands().sealTask(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return requireStartedTaskQueries().validateTaskState(taskId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return requireStartedTaskQueries().resolveTaskState(taskId);
    }

    public TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit) {
        ensureRunning();
        return config.readTaskResults(taskId, afterSeq, limit);
    }

    public TaskWorkStatsSnapshot getTaskWorkStats(String taskId) {
        ensureRunning();
        return config.getTaskWorkStats(taskId);
    }

    public List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId) {
        ensureRunning();
        return config.getActiveLeases(taskId);
    }

    public Optional<TaskWorkFinalSnapshot> getVisibleTaskResultByMessageId(String taskId, String messageId) {
        ensureRunning();
        return config.getVisibleTaskResultByMessageId(taskId, messageId);
    }

    public long countVisibleTaskResults(String taskId) {
        ensureRunning();
        return config.countVisibleTaskResults(taskId);
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        return requireStartedWorkerControlRuntime().applyWorkerCapabilityReport(report);
    }

    public WorkerStateProjectionResult applyWorkerStateReport(WorkerStateReport report) {
        return requireStartedWorkerControlRuntime().applyWorkerStateReport(report);
    }

    public Optional<WorkerStateProjection> workerStateProjection(String workerId) {
        return requireStartedWorkerControlRuntime().workerStateProjection(workerId);
    }

    public List<WorkerStateProjection> workerStateProjections() {
        return requireStartedWorkerControlRuntime().workerStateProjections();
    }

    public WorkerCommandLifecycleResult requestWorkerCommand(WorkerCommandRequest request) {
        return requireStartedWorkerControlRuntime().requestWorkerCommand(request);
    }

    public WorkerCommandLifecycleResult applyWorkerCommandAcknowledgement(WorkerCommandAcknowledgement acknowledgement) {
        return requireStartedWorkerControlRuntime().applyWorkerCommandAcknowledgement(acknowledgement);
    }

    public List<WorkerCommandRecord> claimPendingWorkerCommands(String workerId, int maxCommands) {
        return requireStartedWorkerControlRuntime().claimPendingWorkerCommands(workerId, maxCommands);
    }

    public Optional<WorkerCommandRecord> workerCommand(String commandId) {
        return requireStartedWorkerControlRuntime().workerCommand(commandId);
    }

    public List<WorkerCommandRecord> workerCommandsForWorker(String workerId) {
        return requireStartedWorkerControlRuntime().workerCommandsForWorker(workerId);
    }

    public TaskStageEvidenceResult applyTaskStageEvidence(String taskId,
                                                          String messageId,
                                                          String stageName,
                                                          long stageVersion,
                                                          String stageStatus,
                                                          String detail,
                                                          Instant observedAt,
                                                          Map<String, Object> attributes) {
        return requireStartedTaskStageEvidenceService().applyEvidence(
                taskId,
                messageId,
                stageName,
                stageVersion,
                stageStatus,
                detail,
                observedAt,
                attributes);
    }

    public Optional<TaskStageProjection> taskStageProjection(String taskId, String messageId, String stageName) {
        return requireStartedTaskStageEvidenceService().projection(taskId, messageId, stageName);
    }

    public List<TaskStageProjection> taskStageProjectionsForMessage(String taskId, String messageId) {
        return requireStartedTaskStageEvidenceService().projectionsForMessage(taskId, messageId);
    }

    public List<RuleDefinition> listRules() {
        return config.getRuleStorage().getAllRules();
    }

    public void replaceRules(Collection<RuleDefinition> rules) {
        RuleStorage ruleStorage = config.getRuleStorage();
        ruleStorage.clear();
        ruleStorage.addRules(List.copyOf(rules));
    }

    public List<RuleType> registeredEvaluatorTypes() {
        return config.getRuleEvaluatorRegistry().registeredEvaluatorTypes();
    }

    public boolean hasWorkerExclusiveLease(String workerId) {
        return config.getWorkerAdmissionRuntime().hasWorkerExclusiveLease(workerId);
    }

    public void addTaskWorkLogicallyFinalListener(Consumer<TaskWorkFinalNotification> listener) {
        requireStartedTaskEvents().addTaskWorkLogicallyFinalListener((task, event) -> listener.accept(
                new TaskWorkFinalNotification(
                        event.taskId(),
                        task == null ? Map.of() : task.getSharedConfig(),
                        event.messageId(),
                        event.status() == null ? null : event.status().name(),
                        event.finalReason() == null ? null : event.finalReason().name(),
                        event.retryCount(),
                        event.errorCode(),
                        event.errorMessage(),
                        event.payloadRef(),
                        event.output()
                )));
    }

    public void addTaskWorkAttemptClosedListener(Consumer<TaskWorkAttemptClosedNotification> listener) {
        requireStartedTaskEvents().addTaskWorkAttemptClosedListener((task, event) -> listener.accept(
                new TaskWorkAttemptClosedNotification(
                        event.taskId(),
                        task == null ? Map.of() : task.getSharedConfig(),
                        event.messageId(),
                        event.attemptId(),
                        event.attemptNo(),
                        event.workerId(),
                        event.batchId(),
                        event.status() == null ? null : event.status().name(),
                        event.finalReason() == null ? null : event.finalReason().name()
                )));
    }

    private TaskCommandService requireStartedTaskCommands() {
        if (taskCommands == null) {
            throw new IllegalStateException("MassEngine has not been started; task command service is unavailable");
        }
        return taskCommands;
    }

    private TaskQueryService requireStartedTaskQueries() {
        ensureRunning();
        TaskQueryService taskQueries = config.getTaskQueryService();
        if (taskQueries == null) {
            throw new IllegalStateException("Task query service is unavailable for this engine");
        }
        return taskQueries;
    }

    private TaskEventService requireStartedTaskEvents() {
        ensureRunning();
        TaskEventService taskEvents = config.getTaskEventService();
        if (taskEvents == null) {
            throw new IllegalStateException("Task event service is unavailable for this engine");
        }
        return taskEvents;
    }

    private WorkerControlRuntime requireStartedWorkerControlRuntime() {
        ensureRunning();
        WorkerControlRuntime runtime = config.getWorkerControlRuntime();
        if (runtime == null) {
            throw new IllegalStateException("Worker control runtime is unavailable for this engine");
        }
        return runtime;
    }

    private TaskStageEvidenceService requireStartedTaskStageEvidenceService() {
        ensureRunning();
        TaskStageEvidenceService service = config.getTaskStageEvidenceService();
        if (service == null) {
            throw new IllegalStateException("Task stage evidence service is unavailable for this engine");
        }
        return service;
    }

    private void ensureRunning() {
        if (!running) {
            throw new IllegalStateException("MassEngine has not been started");
        }
    }

    public record TaskWorkFinalNotification(String taskId,
                                            Map<String, Object> sharedConfig,
                                            String messageId,
                                            String status,
                                            String finalReason,
                                            int retryCount,
                                            String errorCode,
                                            String errorMessage,
                                            String payloadRef,
                                            Map<String, Object> output) {
    }

    public record TaskWorkAttemptClosedNotification(String taskId,
                                                    Map<String, Object> sharedConfig,
                                                    String messageId,
                                                    String attemptId,
                                                    int attemptNo,
                                                    String workerId,
                                                    String batchId,
                                                    String status,
                                                    String finalReason) {
    }

}
