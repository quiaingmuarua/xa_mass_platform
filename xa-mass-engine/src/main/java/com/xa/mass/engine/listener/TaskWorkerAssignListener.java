package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategy;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Listens for task assignment events and delegates worker matching to a pluggable strategy.
 */
public class TaskWorkerAssignListener {
    private static final Logger log = LoggerFactory.getLogger(TaskWorkerAssignListener.class);

    private final TaskWorkerMatchingStrategy matchingStrategy;
    private final WorkerManager workerManager;
    private final TaskDispatchBinder dispatchBinder;
    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final TaskAssignmentEventSink assignmentEventSink;
    private final TraceEventLogger traceEventLogger;

    public TaskWorkerAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    WorkerManager workerManager,
                                    TaskDispatchBinder dispatchBinder,
                                    AssignmentDiagnosticRecorder recordService,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(ruleManager, workerManager, dispatchBinder, recordService, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    WorkerManager workerManager,
                                    TaskDispatchBinder dispatchBinder,
                                    AssignmentDiagnosticRecorder recordService,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this(new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService, traceEventLogger),
                workerManager, dispatchBinder, assignmentRuntime, assignmentEventSink, traceEventLogger);
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerManager workerManager,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(matchingStrategy, workerManager, dispatchBinder, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerManager workerManager,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this.matchingStrategy = matchingStrategy;
        this.workerManager = workerManager;
        this.dispatchBinder = dispatchBinder;
        this.assignmentRuntime = assignmentRuntime;
        this.assignmentEventSink = assignmentEventSink;
        this.traceEventLogger = traceEventLogger;
    }

    /**
     * Processes a task assignment attempt.
     */
    public boolean onTaskAssign(Task task) {
        TaskStatus initialStatus = task.getStatus();
        if (initialStatus != TaskStatus.READY && initialStatus != TaskStatus.RUNNING) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "task status is not dispatchable: " + initialStatus, null);
            emitAssignmentSummary(task, initialStatus, 0, 0, 0, 0,
                    0, 0, 0, 0,
                    "task status is not dispatchable: " + initialStatus, "SKIPPED");
            return false;
        }

        int readyWorkCount = assignmentRuntime.countDispatchReadyWork(task.getTid());
        if (readyWorkCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no runtime-ready work", null);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, 0, 0, 0,
                    0, 0, 0, 0,
                    "no runtime-ready work", "SKIPPED");
            return false;
        }

        int desiredDispatchWorkerCount = getDesiredDispatchWorkerCount(task, readyWorkCount);
        int requiredStartWorkerCount = initialStatus == TaskStatus.READY
                ? getRequiredStartWorkerCount(task)
                : 1;
        int matchRequestCount = resolveMatchRequestCount(task, desiredDispatchWorkerCount, requiredStartWorkerCount);
        List<MatchedWorkerContext> matched = matchingStrategy.matchWorkers(task, matchRequestCount);
        log.info("[WorkerAssign] Strategy {} matched {} worker-context candidates for task {}",
                matchingStrategy.getClass().getSimpleName(), matched.size(), task.getTid());
        if (matched.isEmpty()) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no matched worker-context candidates", requiredStartWorkerCount);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    0, 0, 0, 0,
                    "no matched worker-context candidates", "SKIPPED");
            return false;
        }
        if (initialStatus == TaskStatus.READY && matched.size() < requiredStartWorkerCount) {
            log.info("[WorkerAssign] Keep task {} in READY because matched workers {} are below required minimum {}",
                    task.getTid(), matched.size(), requiredStartWorkerCount);
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "matched workers below minimum start gate", requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "matched workers below minimum start gate", "SKIPPED");
            return false;
        }
        if (task.getStatus() != initialStatus) {
            log.info("[WorkerAssign] Skip dispatch for task {} because status changed from {} to {} during matching",
                    task.getTid(), initialStatus, task.getStatus());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "task status changed during matching from " + initialStatus + " to " + task.getStatus(),
                    requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "task status changed during matching from " + initialStatus + " to " + task.getStatus(), "SKIPPED");
            return false;
        }

        int dispatchCandidateLimit = usesTaskLevelEventCapability(task) ? desiredDispatchWorkerCount : matched.size();
        List<MatchedWorkerContext> dispatchCandidates = matched.subList(0, Math.min(matched.size(), dispatchCandidateLimit));
        if (dispatchCandidates.isEmpty()) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no dispatch candidates remained after capacity trim", requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "no dispatch candidates remained after capacity trim", "SKIPPED");
            return false;
        }
        unlockWorkers(matched.subList(dispatchCandidates.size(), matched.size()));

        List<TaskDispatchBinding> dispatchedBindings = dispatchBinder.bindDispatches(task, List.copyOf(dispatchCandidates));
        long usedWorkerCount = dispatchedBindings.stream()
                .map(TaskDispatchBinding::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        if (usedWorkerCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "matched candidates produced no bound work", requiredStartWorkerCount);
            unlockWorkers(dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), 0,
                    "matched candidates produced no bound work", "SKIPPED");
            return false;
        }

        traceEventLogger.dispatchRequested(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                "matched candidates produced dispatchable work");

        task.setPeakAssignedWorkerCount(Math.max(task.getPeakAssignedWorkerCount(), (int) usedWorkerCount));
        if (initialStatus == TaskStatus.READY && !task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[WorkerAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            unlockWorkers(dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                    "task failed to transition from READY to RUNNING after dispatch", "FAILED");
            return false;
        }
        if (initialStatus == TaskStatus.READY) {
            traceEventLogger.taskStatusTransition(
                    task.getTid(),
                    initialStatus,
                    task.getStatus(),
                    "ASSIGNMENT_SUCCEEDED",
                    "TaskWorkerAssignListener",
                    "matched workers dispatched"
            );
        }
        emitAssignmentSummary(task, initialStatus, readyWorkCount, desiredDispatchWorkerCount,
                requiredStartWorkerCount, matchRequestCount,
                matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                "matched workers dispatched", "SUCCESS");
        assignmentRuntime.updateTask(task);
        assignmentEventSink.publishTaskAssigned(task);
        return true;
    }

    private int getDesiredDispatchWorkerCount(Task task, int readyWorkCount) {
        int remainingMessages = Math.max(readyWorkCount, 1);
        return Math.max(1, (int) Math.ceil((double) remainingMessages / task.getExecutionSpec().getBatchSize()));
    }

    private int getRequiredStartWorkerCount(Task task) {
        return Math.max(task.getMinRequiredWorkerCount(), 1);
    }

    private int resolveMatchRequestCount(Task task,
                                         int desiredDispatchWorkerCount,
                                         int requiredStartWorkerCount) {
        int baseline = Math.max(requiredStartWorkerCount, desiredDispatchWorkerCount);
        if (usesTaskLevelEventCapability(task)) {
            return baseline;
        }
        int candidateCount = workerManager.findWorkerCandidates(task).size();
        return Math.max(baseline, Math.max(candidateCount, 1));
    }

    private boolean usesTaskLevelEventCapability(Task task) {
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        return eventCode != null && !eventCode.isBlank();
    }

    private void unlockWorkers(List<MatchedWorkerContext> workers) {
        for (String workerId : workers.stream()
                .map(MatchedWorkerContext::getWorkerId)
                .distinct()
                .collect(Collectors.toList())) {
            workerManager.unlockWorker(workerId);
            traceEventLogger.workerLockReleased(null, workerId, "UNLOCK_WORKER", "TaskWorkerAssignListener",
                    "surplus or skipped dispatch candidate");
        }
    }

    private void emitAssignmentSummary(Task task,
                                       TaskStatus initialStatus,
                                       int pendingDispatchCount,
                                       int desiredDispatchWorkerCount,
                                       int requiredStartWorkerCount,
                                       int requestedMatchCount,
                                       int matchedWorkerCount,
                                       int dispatchCandidateCount,
                                       int dispatchedMessageCount,
                                       int usedWorkerCount,
                                       String reason,
                                       String result) {
        traceEventLogger.assignmentSummary(
                task,
                initialStatus,
                task.getStatus(),
                pendingDispatchCount,
                desiredDispatchWorkerCount,
                requiredStartWorkerCount,
                requestedMatchCount,
                matchedWorkerCount,
                dispatchCandidateCount,
                dispatchedMessageCount,
                usedWorkerCount,
                task.getPeakAssignedWorkerCount(),
                "ON_TASK_ASSIGN",
                "TaskWorkerAssignListener",
                reason,
                result
        );
    }
}

