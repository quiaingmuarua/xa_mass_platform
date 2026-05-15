package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.assignment.AssignmentAllocationDecision;
import com.xa.mass.engine.assignment.AssignmentAllocationOutcome;
import com.xa.mass.engine.assignment.AssignmentAllocationPlan;
import com.xa.mass.engine.assignment.AssignmentAllocationPolicy;
import com.xa.mass.engine.assignment.AssignmentAllocationRequest;
import com.xa.mass.engine.assignment.DefaultAssignmentAllocationPolicy;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
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
    private final AssignmentAllocationPolicy allocationPolicy;

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
        this(matchingStrategy, workerManager, dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, new DefaultAssignmentAllocationPolicy());
    }

    TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                             WorkerManager workerManager,
                             TaskDispatchBinder dispatchBinder,
                             TaskAssignmentRuntimePort assignmentRuntime,
                             TaskAssignmentEventSink assignmentEventSink,
                             TraceEventLogger traceEventLogger,
                             AssignmentAllocationPolicy allocationPolicy) {
        this.matchingStrategy = matchingStrategy;
        this.workerManager = workerManager;
        this.dispatchBinder = dispatchBinder;
        this.assignmentRuntime = assignmentRuntime;
        this.assignmentEventSink = assignmentEventSink;
        this.traceEventLogger = traceEventLogger;
        this.allocationPolicy = allocationPolicy == null ? new DefaultAssignmentAllocationPolicy() : allocationPolicy;
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

        AssignmentAllocationPlan allocationPlan = allocationPolicy.plan(new AssignmentAllocationRequest(
                task,
                initialStatus,
                readyWorkCount,
                workerManager.findWorkerCandidates(task).size(),
                usesTaskLevelEventCapability(task)
        ));
        List<WorkerSchedulingCandidate> matched = matchingStrategy.matchWorkers(task, allocationPlan.requestedMatchCount());
        log.info("[WorkerAssign] Strategy {} matched {} worker scheduling candidates for task {}",
                matchingStrategy.getClass().getSimpleName(), matched.size(), task.getTid());
        AssignmentAllocationDecision allocationDecision = allocationPolicy.decide(allocationPlan, task.getStatus(), matched);
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.NO_MATCH) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    0, 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.BELOW_MIN_START_GATE) {
            log.info("[WorkerAssign] Keep task {} in READY because matched workers {} are below required minimum {}",
                    task.getTid(), matched.size(), allocationPlan.requiredStartWorkerCount());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    matched.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.TASK_STATUS_CHANGED) {
            log.info("[WorkerAssign] Skip dispatch for task {} because status changed from {} to {} during matching",
                    task.getTid(), initialStatus, task.getStatus());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(),
                    allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    matched.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }

        List<WorkerSchedulingCandidate> dispatchCandidates = allocationDecision.dispatchCandidates();
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.NO_DISPATCH_CANDIDATES) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    matched.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        releaseReservedAndUnlockWorkers(task, matched.subList(dispatchCandidates.size(), matched.size()));

        List<TaskDispatchBinding> dispatchedBindings = dispatchBinder.bindDispatches(task, List.copyOf(dispatchCandidates));
        long usedWorkerCount = dispatchedBindings.stream()
                .map(TaskDispatchBinding::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        if (usedWorkerCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "matched candidates produced no bound work", allocationPlan.requiredStartWorkerCount());
            unlockWorkers(dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
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
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
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
        emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                "matched workers dispatched", "SUCCESS");
        assignmentRuntime.updateTask(task);
        assignmentEventSink.publishTaskAssigned(task);
        return true;
    }

    private boolean usesTaskLevelEventCapability(Task task) {
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        return eventCode != null && !eventCode.isBlank();
    }

    private void unlockWorkers(List<WorkerSchedulingCandidate> workers) {
        for (String workerId : workers.stream()
                .map(WorkerSchedulingCandidate::getWorkerId)
                .distinct()
                .collect(Collectors.toList())) {
            workerManager.unlockWorker(workerId);
            traceEventLogger.workerLockReleased(null, workerId, "UNLOCK_WORKER", "TaskWorkerAssignListener",
                    "surplus or skipped dispatch candidate");
        }
    }

    private void releaseReservedAndUnlockWorkers(Task task, List<WorkerSchedulingCandidate> workers) {
        for (String workerId : workers.stream()
                .map(WorkerSchedulingCandidate::getWorkerId)
                .distinct()
                .collect(Collectors.toList())) {
            workerManager.releaseWorkerReservation(workerId, task.getTid());
            workerManager.unlockWorker(workerId);
            traceEventLogger.workerLockReleased(task.getTid(), workerId, "UNLOCK_WORKER", "TaskWorkerAssignListener",
                    "surplus or skipped dispatch candidate");
        }
    }

    private void emitAssignmentSummary(Task task,
                                       TaskStatus initialStatus,
                                       int pendingDispatchCount,
                                       AssignmentAllocationPlan allocationPlan,
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
                allocationPlan.desiredDispatchWorkerCount(),
                allocationPlan.requiredStartWorkerCount(),
                allocationPlan.requestedMatchCount(),
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

