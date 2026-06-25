package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.assignment.AssignmentAllocationDecision;
import com.xa.mass.engine.assignment.AssignmentAllocationOutcome;
import com.xa.mass.engine.assignment.AssignmentAllocationPlan;
import com.xa.mass.engine.assignment.AssignmentAllocationPolicy;
import com.xa.mass.engine.assignment.AssignmentAllocationRequest;
import com.xa.mass.engine.assignment.DefaultAssignmentAllocationPolicy;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionIntent;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRequest;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Listens for task assignment events and asks worker-runtime for selected worker
 * handles.
 */
public class TaskWorkerAssignListener {
    private static final Logger log = LoggerFactory.getLogger(TaskWorkerAssignListener.class);

    private final WorkerSelectionRuntime workerSelectionRuntime;
    private final TaskDispatchBinder dispatchBinder;
    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final TaskAssignmentEventSink assignmentEventSink;
    private final TraceEventLogger traceEventLogger;
    private final AssignmentDiagnosticRecorder assignmentDiagnosticRecorder;
    private final AssignmentAllocationPolicy allocationPolicy;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerDispatchResourceReleaser resourceReleaser;
    private final SchedulingPlaneResolver schedulingPlaneResolver;

    public TaskWorkerAssignListener(WorkerSelectionRuntime workerSelectionRuntime,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(workerSelectionRuntime, dispatchBinder, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(WorkerSelectionRuntime workerSelectionRuntime,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this(workerSelectionRuntime, dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, null, new DefaultAssignmentAllocationPolicy(),
                new DefaultWorkerDispatchResourcePolicy(), null, new DefaultSchedulingPlaneResolver());
    }

    TaskWorkerAssignListener(WorkerSelectionRuntime workerSelectionRuntime,
                             TaskDispatchBinder dispatchBinder,
                             TaskAssignmentRuntimePort assignmentRuntime,
                             TaskAssignmentEventSink assignmentEventSink,
                             TraceEventLogger traceEventLogger,
                             AssignmentAllocationPolicy allocationPolicy) {
        this(workerSelectionRuntime, dispatchBinder, assignmentRuntime, assignmentEventSink,
                traceEventLogger, null, allocationPolicy, new DefaultWorkerDispatchResourcePolicy(), null,
                new DefaultSchedulingPlaneResolver());
    }

    public TaskWorkerAssignListener(WorkerSelectionRuntime workerSelectionRuntime,
                                    TaskDispatchBinder dispatchBinder,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger,
                                    AssignmentDiagnosticRecorder assignmentDiagnosticRecorder,
                                    AssignmentAllocationPolicy allocationPolicy,
                                    WorkerDispatchResourcePolicy resourcePolicy,
                                    WorkerDispatchResourceReleaser resourceReleaser,
                                    SchedulingPlaneResolver schedulingPlaneResolver) {
        SchedulingPlaneResolver resolvedSchedulingPlaneResolver =
                Objects.requireNonNull(schedulingPlaneResolver, "schedulingPlaneResolver");
        this.workerSelectionRuntime = Objects.requireNonNull(workerSelectionRuntime, "workerSelectionRuntime");
        this.dispatchBinder = Objects.requireNonNull(dispatchBinder, "dispatchBinder");
        this.assignmentRuntime = Objects.requireNonNull(assignmentRuntime, "assignmentRuntime");
        this.assignmentEventSink = Objects.requireNonNull(assignmentEventSink, "assignmentEventSink");
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
        this.assignmentDiagnosticRecorder = assignmentDiagnosticRecorder;
        this.allocationPolicy = allocationPolicy == null
                ? new DefaultAssignmentAllocationPolicy(null, resolvedSchedulingPlaneResolver)
                : allocationPolicy;
        this.resourcePolicy = resourcePolicy == null
                ? new DefaultWorkerDispatchResourcePolicy(resolvedSchedulingPlaneResolver)
                : resourcePolicy;
        this.resourceReleaser = resourceReleaser == null
                ? new WorkerDispatchResourceReleaser(workerSelectionRuntime, this.resourcePolicy, traceEventLogger)
                : resourceReleaser;
        this.schedulingPlaneResolver = resolvedSchedulingPlaneResolver;
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
                assignmentRuntime.countActiveDispatchWorkers(task.getTid())
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

        WorkerSelectionResult selectionResult = workerSelectionRuntime.selectAndReserve(selectionRequest(task, allocationPlan));
        recordSelectionOutcome(task, selectionResult);
        List<SelectedWorkerHandle> selectedWorkers = selectionResult.selectedWorkers();
        emitAcceptedWorkerMatches(task, selectedWorkers);
        log.info("[WorkerAssign] Worker-runtime selected {} workers for task {}",
                selectedWorkers.size(), task.getTid());

        AssignmentAllocationDecision allocationDecision = allocationPolicy.decide(allocationPlan, task.getStatus(), selectedWorkers);
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.BUDGET_EXHAUSTED) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, selectedWorkers);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), 0, 0, 0,
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
            log.info("[WorkerAssign] Keep task {} in READY because selected workers {} are below required minimum {}",
                    task.getTid(), selectedWorkers.size(), allocationPlan.requiredStartWorkerCount());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, selectedWorkers);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.TASK_STATUS_CHANGED) {
            log.info("[WorkerAssign] Skip dispatch for task {} because status changed from {} to {} during selection",
                    task.getTid(), initialStatus, task.getStatus());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(),
                    allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, selectedWorkers);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }

        List<SelectedWorkerHandle> dispatchCandidates = allocationDecision.dispatchCandidates();
        if (allocationDecision.outcome() == AssignmentAllocationOutcome.NO_DISPATCH_CANDIDATES) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    allocationDecision.reason(), allocationPlan.requiredStartWorkerCount());
            releaseReservedAndUnlockWorkers(task, selectedWorkers);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), 0, 0, 0,
                    allocationDecision.reason(), "SKIPPED");
            return false;
        }
        releaseReservedAndUnlockWorkers(task, selectedWorkers.subList(dispatchCandidates.size(), selectedWorkers.size()));

        List<TaskDispatchBinding> dispatchedBindings = dispatchBinder.bindDispatches(task, List.copyOf(dispatchCandidates));
        long usedWorkerCount = dispatchedBindings.stream()
                .map(TaskDispatchBinding::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        if (usedWorkerCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "selected workers produced no bound work", allocationPlan.requiredStartWorkerCount());
            releaseLocksIfExclusive(task, dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), dispatchCandidates.size(), dispatchedBindings.size(), 0,
                    "selected workers produced no bound work", "SKIPPED");
            return false;
        }

        traceEventLogger.dispatchRequested(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                "selected workers produced dispatchable work");

        task.setPeakAssignedWorkerCount(Math.max(task.getPeakAssignedWorkerCount(), (int) usedWorkerCount));
        if (initialStatus == TaskStatus.READY && !task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[WorkerAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            releaseLocksIfExclusive(task, dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                    selectedWorkers.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
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
                    "selected workers dispatched"
            );
        }
        emitAssignmentSummary(task, initialStatus, readyWorkCount, allocationPlan,
                selectedWorkers.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                "selected workers dispatched", "SUCCESS");
        assignmentRuntime.updateTask(task);
        assignmentEventSink.publishTaskAssigned(task);
        return true;
    }

    private WorkerSelectionRequest selectionRequest(Task task, AssignmentAllocationPlan allocationPlan) {
        ResolvedWorkerSchedulingPolicy workerPolicy = schedulingPlaneResolver.resolve(task).workerSchedulingPolicy();
        WorkerSelectionIntent intent = new WorkerSelectionIntent(
                workerPolicy.project(),
                workerPolicy.eventCode(),
                workerPolicy.workerGroupIds(),
                workerPolicy.routingCode(),
                workerPolicy.routeAttributes(),
                workerPolicy.targetWorkerId(),
                workerPolicy.targetWorkerAttributes()
        );
        return new WorkerSelectionRequest(
                task.getTid(),
                intent,
                allocationPlan.requestedMatchCount(),
                resourcePolicy.usageForTask(task).exclusiveWorkerLock()
        );
    }

    private void recordSelectionOutcome(Task task, WorkerSelectionResult selectionResult) {
        if (assignmentDiagnosticRecorder == null || selectionResult == null) {
            return;
        }
        String reason = selectionResult.selectedCount() > 0
                ? "worker-runtime selected workers"
                : "worker-runtime selected no workers";
        assignmentDiagnosticRecorder.recordWorkerSelectionOutcome(
                task,
                selectionResult,
                selectionResult.selectedCount() > 0
                        ? com.xa.mass.base.enums.assignment.AssignmentResult.SUCCESS
                        : com.xa.mass.base.enums.assignment.AssignmentResult.SKIPPED,
                reason,
                Map.of()
        );
    }

    private void emitAcceptedWorkerMatches(Task task, List<SelectedWorkerHandle> selectedWorkers) {
        if (selectedWorkers == null || selectedWorkers.isEmpty()) {
            return;
        }
        for (int index = 0; index < selectedWorkers.size(); index++) {
            traceEventLogger.workerMatchAccepted(
                    task,
                    selectedWorkers.get(index),
                    index + 1,
                    "ON_TASK_ASSIGN",
                    "TaskWorkerAssignListener",
                    acceptedWorkerMatchReason(selectedWorkers.get(index))
            );
        }
    }

    private static String acceptedWorkerMatchReason(SelectedWorkerHandle selectedWorker) {
        if (selectedWorker != null && !selectedWorker.exclusiveWorkerLock()) {
            return "worker-runtime selected worker; capacity reserved";
        }
        return "worker-runtime selected worker";
    }

    private void releaseLocksIfExclusive(Task task, List<SelectedWorkerHandle> workers) {
        resourceReleaser.releaseLocks(task, workers,
                "UNLOCK_WORKER", "TaskWorkerAssignListener", "surplus or skipped dispatch candidate");
    }

    private void releaseReservedAndUnlockWorkers(Task task, List<SelectedWorkerHandle> workers) {
        resourceReleaser.releaseReservationsAndLocks(task, workers,
                "UNLOCK_WORKER", "TaskWorkerAssignListener", "surplus or skipped dispatch candidate");
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
