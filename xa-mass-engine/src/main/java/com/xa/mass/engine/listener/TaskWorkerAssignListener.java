package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.strategy.WorkerTaskSelectorFactory;
import com.xa.mass.engine.assignment.AssignmentAllocationDecision;
import com.xa.mass.engine.assignment.AssignmentAllocationOutcome;
import com.xa.mass.engine.assignment.AssignmentAllocationPlan;
import com.xa.mass.engine.assignment.AssignmentAllocationPolicy;
import com.xa.mass.engine.assignment.AssignmentAllocationRequest;
import com.xa.mass.engine.assignment.DefaultAssignmentAllocationPolicy;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerWarmHintRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Listens for task assignment events and delegates worker matching to a pluggable strategy.
 */
public class TaskWorkerAssignListener {
    private static final Logger log = LoggerFactory.getLogger(TaskWorkerAssignListener.class);

    private final TaskWorkerMatchingStrategy matchingStrategy;
    private final WorkerAdmissionRuntime workerAdmissionRuntime;
    private final WorkerWarmHintRuntime workerWarmHintRuntime;
    private final TaskDispatchBinder dispatchBinder;
    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final TaskAssignmentEventSink assignmentEventSink;
    private final TraceEventLogger traceEventLogger;
    private final AssignmentAllocationPolicy allocationPolicy;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerDispatchResourceReleaser resourceReleaser;

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerAdmissionRuntime workerAdmissionRuntime,
                                    WorkerWarmHintRuntime workerWarmHintRuntime,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(matchingStrategy, workerAdmissionRuntime, workerWarmHintRuntime,
                dispatchBinder, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerAdmissionRuntime workerAdmissionRuntime,
                                    WorkerWarmHintRuntime workerWarmHintRuntime,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this(matchingStrategy, workerAdmissionRuntime, workerWarmHintRuntime,
                dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, new DefaultAssignmentAllocationPolicy());
    }

    TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                             WorkerAdmissionRuntime workerAdmissionRuntime,
                             WorkerWarmHintRuntime workerWarmHintRuntime,
                             TaskDispatchBinder dispatchBinder,
                             TaskAssignmentRuntimePort assignmentRuntime,
                             TaskAssignmentEventSink assignmentEventSink,
                             TraceEventLogger traceEventLogger,
                             AssignmentAllocationPolicy allocationPolicy) {
        this(matchingStrategy, workerAdmissionRuntime, workerWarmHintRuntime,
                dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, allocationPolicy, new DefaultWorkerDispatchResourcePolicy());
    }

    TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                             WorkerAdmissionRuntime workerAdmissionRuntime,
                             WorkerWarmHintRuntime workerWarmHintRuntime,
                             TaskDispatchBinder dispatchBinder,
                             TaskAssignmentRuntimePort assignmentRuntime,
                             TaskAssignmentEventSink assignmentEventSink,
                             TraceEventLogger traceEventLogger,
                             AssignmentAllocationPolicy allocationPolicy,
                             WorkerDispatchResourcePolicy resourcePolicy) {
        this(matchingStrategy, workerAdmissionRuntime, workerWarmHintRuntime,
                dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, allocationPolicy, resourcePolicy, null);
    }

    TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                             WorkerAdmissionRuntime workerAdmissionRuntime,
                             WorkerWarmHintRuntime workerWarmHintRuntime,
                             TaskDispatchBinder dispatchBinder,
                             TaskAssignmentRuntimePort assignmentRuntime,
                             TaskAssignmentEventSink assignmentEventSink,
                             TraceEventLogger traceEventLogger,
                             AssignmentAllocationPolicy allocationPolicy,
                             WorkerDispatchResourcePolicy resourcePolicy,
                             WorkerDispatchResourceReleaser resourceReleaser) {
        this.matchingStrategy = matchingStrategy;
        this.workerAdmissionRuntime = workerAdmissionRuntime;
        this.workerWarmHintRuntime = workerWarmHintRuntime;
        this.dispatchBinder = dispatchBinder;
        this.assignmentRuntime = assignmentRuntime;
        this.assignmentEventSink = assignmentEventSink;
        this.traceEventLogger = traceEventLogger;
        this.allocationPolicy = allocationPolicy == null ? new DefaultAssignmentAllocationPolicy() : allocationPolicy;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.resourceReleaser = resourceReleaser == null
                ? new WorkerDispatchResourceReleaser(workerAdmissionRuntime, this.resourcePolicy, traceEventLogger)
                : resourceReleaser;
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
                workerAdmissionRuntime.getActiveWorkerCountForTask(task.getTid())
        ));
        if (allocationPlan.requestedMatchCount() <= 0) {
            AssignmentAllocationDecision allocationDecision =
                    allocationPolicy.decide(allocationPlan, task.getStatus(), List.of());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    0, 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        List<WorkerSchedulingCandidate> matched = matchingStrategy.matchWorkers(task, allocationPlan.requestedMatchCount());
        log.info("[WorkerAssign] Strategy {} matched {} worker scheduling candidates for task {}",
                matchingStrategy.getClass().getSimpleName(), matched.size(), task.getTid());
        AssignmentAllocationDecision allocationDecision = allocationPolicy.decide(allocationPlan, task.getStatus(), matched);
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.BUDGET_EXHAUSTED) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, matched);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    matched.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
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
            releaseLocksIfExclusive(task, dispatchCandidates);
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
            releaseLocksIfExclusive(task, dispatchCandidates);
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
        recordWarmCandidatesForBoundWorkers(task, dispatchCandidates, dispatchedBindings);
        emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                "matched workers dispatched", "SUCCESS");
        assignmentRuntime.updateTask(task);
        assignmentEventSink.publishTaskAssigned(task);
        return true;
    }

    private void releaseLocksIfExclusive(Task task, List<WorkerSchedulingCandidate> workers) {
        resourceReleaser.releaseLocks(task, workers,
                "UNLOCK_WORKER", "TaskWorkerAssignListener", "surplus or skipped dispatch candidate");
    }

    private void releaseReservedAndUnlockWorkers(Task task, List<WorkerSchedulingCandidate> workers) {
        resourceReleaser.releaseReservationsAndLocks(task, workers,
                "UNLOCK_WORKER", "TaskWorkerAssignListener", "surplus or skipped dispatch candidate");
    }

    private void recordWarmCandidatesForBoundWorkers(Task task,
                                                     List<WorkerSchedulingCandidate> dispatchCandidates,
                                                     List<TaskDispatchBinding> dispatchedBindings) {
        if (task == null || dispatchCandidates == null || dispatchCandidates.isEmpty()
                || dispatchedBindings == null || dispatchedBindings.isEmpty()) {
            return;
        }
        Set<String> usedWorkerIds = new LinkedHashSet<>();
        for (TaskDispatchBinding binding : dispatchedBindings) {
            if (binding != null && binding.workerId() != null && !binding.workerId().isBlank()) {
                usedWorkerIds.add(binding.workerId());
            }
        }
        if (usedWorkerIds.isEmpty()) {
            return;
        }
        Set<String> recordedWorkerIds = new LinkedHashSet<>();
        for (WorkerSchedulingCandidate candidate : dispatchCandidates) {
            if (candidate == null || candidate.getWorkerId() == null || candidate.getWorkerId().isBlank()) {
                continue;
            }
            if (usedWorkerIds.contains(candidate.getWorkerId()) && recordedWorkerIds.add(candidate.getWorkerId())) {
                workerWarmHintRuntime.recordWarmCandidate(
                        WorkerTaskSelectorFactory.fromTask(task),
                        candidate.getCandidateRow());
            }
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
                allocationPlan.workerBudget(),
                allocationPlan.currentTaskWorkerCount(),
                allocationPlan.budgetLimited(),
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
                null,
                0,
                false,
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
