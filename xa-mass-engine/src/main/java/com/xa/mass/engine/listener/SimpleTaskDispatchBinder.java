package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskWorkProjectionState.AttemptStatus;
import com.xa.mass.engine.TaskWorkAttemptIdSupport;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.runtime.TaskRuntimeClaimOptionsResolver;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Claims runtime-ready work for matched workers and emits the dispatch queue.
 */
public class SimpleTaskDispatchBinder implements TaskDispatchBinder {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskDispatchBinder.class);
    private static final TaskRuntimeClaimOptionsResolver TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER =
            new TaskRuntimeClaimOptionsResolver();

    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final WorkerManager workerManager;
    private final AssignmentDiagnosticRecorder recordService;
    private final TaskDispatchBatchListener dispatchListener;
    private final TraceEventLogger traceEventLogger;

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerManager workerManager,
                                       AssignmentDiagnosticRecorder recordService) {
        this(assignmentRuntime, workerManager, recordService, null, TraceEventLogger.noop());
    }

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerManager workerManager,
                                       AssignmentDiagnosticRecorder recordService,
                                       TaskDispatchBatchListener dispatchListener) {
        this(assignmentRuntime, workerManager, recordService, dispatchListener, TraceEventLogger.noop());
    }

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerManager workerManager,
                                       AssignmentDiagnosticRecorder recordService,
                                       TaskDispatchBatchListener dispatchListener,
                                       TraceEventLogger traceEventLogger) {
        this.assignmentRuntime = assignmentRuntime;
        this.workerManager = workerManager;
        this.recordService = recordService;
        this.dispatchListener = dispatchListener;
        this.traceEventLogger = traceEventLogger;
    }

    @Override
    public List<TaskDispatchBinding> bindDispatches(Task task, List<MatchedWorkerContext> matchedWorkers) {
        if (matchedWorkers == null || matchedWorkers.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched worker-context candidates were provided", task.getTid());
            traceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Math.max(task.getExecutionSpec().getBatchSize(), 1),
                    "ON_MSG_ASSIGN",
                    "SimpleTaskDispatchBinder",
                    "no matched worker-context candidates were provided",
                    "SKIPPED"
            );
            return List.of();
        }

        int readyWorkCount = assignmentRuntime.countDispatchReadyWork(task.getTid());
        if (readyWorkCount == 0) {
            log.info("[MsgAssign] Skip task {} because there is no runtime-ready work to dispatch", task.getTid());
            traceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    matchedWorkers.size(),
                    0,
                    0,
                    0,
                    0,
                    Math.max(task.getExecutionSpec().getBatchSize(), 1),
                    "ON_MSG_ASSIGN",
                    "SimpleTaskDispatchBinder",
                    "there is no runtime-ready work to dispatch",
                    "SKIPPED"
            );
            return List.of();
        }

        int resolvedWorkerCount = Math.max(matchedWorkers.size(), 1);
        TaskWorkClaimOptions claimOptions = TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER.resolve(
                task,
                resolvedWorkerCount,
                assignmentRuntime.getWorkLeaseSeconds()
        );
        int perWorkerBatchLimit = claimOptions.perWorkerCapacity();
        List<TaskDispatchBinding> dispatchBindings = new ArrayList<>();
        List<DispatchSlot> dispatchSlots = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} matched candidates, readyWorkCount={}, perWorkerBatchLimit={}",
                task.getTid(), matchedWorkers.size(), readyWorkCount, perWorkerBatchLimit);

        for (int i = 0; i < matchedWorkers.size(); i++) {
            MatchedWorkerContext matchedWorker = matchedWorkers.get(i);
            Worker worker = matchedWorker.getWorker();
            WorkerContext workerContext = matchedWorker.getWorkerContext();
            if (!prepareWorkerContextForDispatch(task, workerContext)) {
                log.warn("[MsgAssign] Skip worker {} context {} for task {} because workerContext state is not dispatchable",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        task.getTid());
                workerManager.unlockWorker(worker.getWorkerId());
                traceEventLogger.workerLockReleased(task.getTid(), worker.getWorkerId(),
                        "UNLOCK_WORKER", "SimpleTaskDispatchBinder", "workerContext not dispatchable");
                continue;
            }
            dispatchSlots.add(new DispatchSlot(worker, workerContext));
        }

        List<WorkerClaimTarget> claimTargets = dispatchSlots.stream()
                .map(slot -> new WorkerClaimTarget(
                        slot.worker().getWorkerId(),
                        slot.workerContextId(),
                        slot.batchId(),
                        perWorkerBatchLimit,
                        supportedEventCodes(slot.worker())
                ))
                .collect(Collectors.toList());
        claimOptions = TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER.resolve(
                task,
                Math.max(dispatchSlots.size(), 1),
                assignmentRuntime.getWorkLeaseSeconds()
        );
        List<ClaimedTaskWork> claimed = assignmentRuntime.claimReady(task.getTid(), claimTargets, claimOptions);

        for (ClaimedTaskWork work : claimed) {
            DispatchSlot slot = findSlot(dispatchSlots, work.workerId(), work.batchId());
            if (slot == null) {
                log.warn("[MsgAssign] Skip claimed work {} because dispatch slot was not found", work.messageId());
                continue;
            }
            TaskDispatchBinding dispatchBinding = bindClaimedTaskWork(task, work);
            dispatchBindings.add(dispatchBinding);
            slot.incrementAssigned();

            recordService.recordMessageAssignment(
                    task, slot.worker(), slot.workerContext(), work.messageId(), slot.batchId(),
                    AssignmentResult.SUCCESS, "message assigned",
                    workerManager.isLocked(slot.worker().getWorkerId())
            );
        }

        for (DispatchSlot slot : dispatchSlots) {
            if (slot.assignedCount() == 0) {
                releaseWorkerContextIfIdleForTask(task, slot.workerContext(), "matched worker received no messages");
                workerManager.unlockWorker(slot.worker().getWorkerId());
                traceEventLogger.workerLockReleased(task.getTid(), slot.worker().getWorkerId(),
                        "UNLOCK_WORKER", "SimpleTaskDispatchBinder", "matched worker received no messages");
            }
        }

        int uniqueWorkerCount = (int) dispatchBindings.stream()
                .map(TaskDispatchBinding::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        int uniqueWorkerContextCount = (int) dispatchBindings.stream()
                .map(TaskDispatchBinding::workerContextId)
                .filter(workerContextId -> workerContextId != null && !workerContextId.isBlank())
                .distinct()
                .count();
        traceEventLogger.dispatchBindingSummary(
                task,
                readyWorkCount,
                matchedWorkers.size(),
                dispatchSlots.size(),
                dispatchBindings.size(),
                uniqueWorkerCount,
                uniqueWorkerContextCount,
                perWorkerBatchLimit,
                "ON_MSG_ASSIGN",
                "SimpleTaskDispatchBinder",
                dispatchBindings.isEmpty()
                        ? "matched workers produced no dispatchable bindings"
                        : "runtime work bound to dispatch slots",
                dispatchBindings.isEmpty() ? "SKIPPED" : "SUCCESS"
        );

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected readyWork={})",
                task.getTid(), dispatchBindings.size(), readyWorkCount);

        if (dispatchListener != null && !dispatchBindings.isEmpty()) {
            TaskDispatchContext dispatchContext = TaskDispatchContext.from(task);
            List<TaskDispatchBinding> immutableDispatchBindings = List.copyOf(dispatchBindings);
            try {
                dispatchListener.onTaskDispatchBatch(dispatchContext, immutableDispatchBindings);
            } catch (RuntimeException e) {
                String detail = "dispatch submit failed before transport delivery: "
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null || e.getMessage().isBlank() ? "" : " - " + e.getMessage());
                log.error("[MsgAssign] Dispatch submit failed for task {} with {} bindings; compensating assignment state",
                        task.getTid(), immutableDispatchBindings.size(), e);
                boolean compensated = assignmentRuntime.compensateDispatchSubmitFailure(task, immutableDispatchBindings, detail);
                releaseAssignedWorkerContexts(task, dispatchSlots);
                if (!compensated) {
                    throw new IllegalStateException("dispatch submit compensation failed for task " + task.getTid(), e);
                }
                traceEventLogger.dispatchBindingSummary(
                        task,
                        readyWorkCount,
                        matchedWorkers.size(),
                        dispatchSlots.size(),
                        0,
                        0,
                        0,
                        perWorkerBatchLimit,
                        "ON_MSG_ASSIGN",
                        "SimpleTaskDispatchBinder",
                        "dispatch submit failed and assignment state was compensated for retry",
                        "RETRIED"
                );
                return List.of();
            }
        }
        return List.copyOf(dispatchBindings);
    }

    private static final class DispatchSlot {
        private final Worker worker;
        private final WorkerContext workerContext;
        private final String batchId = java.util.UUID.randomUUID().toString();
        private int assignedCount;

        private DispatchSlot(Worker worker, WorkerContext workerContext) {
            this.worker = worker;
            this.workerContext = workerContext;
        }

        private Worker worker() {
            return worker;
        }

        private WorkerContext workerContext() {
            return workerContext;
        }

        private String workerContextId() {
            return workerContext != null ? workerContext.getWorkerContextId() : null;
        }

        private String batchId() {
            return batchId;
        }

        private int assignedCount() {
            return assignedCount;
        }

        private void incrementAssigned() {
            assignedCount++;
        }
    }

    private DispatchSlot findSlot(List<DispatchSlot> dispatchSlots, String workerId, String batchId) {
        for (DispatchSlot slot : dispatchSlots) {
            if (slot.worker().getWorkerId().equals(workerId) && slot.batchId().equals(batchId)) {
                return slot;
            }
        }
        return null;
    }

    private TaskDispatchBinding bindClaimedTaskWork(Task task, ClaimedTaskWork work) {
        int attemptNo = Math.max(0, work.retryCount()) + 1;
        String attemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(
                work.messageId(),
                attemptNo,
                work.workerId(),
                work.workerContextId(),
                work.batchId()
        );
        traceEventLogger.taskWorkAttemptStatusTransition(
                task.getTid(),
                work.messageId(),
                attemptId,
                attemptNo,
                work.workerId(),
                work.workerContextId(),
                work.batchId(),
                null,
                AttemptStatus.CREATED,
                AttemptStatus.LEASED,
                "BIND_TASK_MESSAGE",
                "SimpleTaskDispatchBinder",
                "attempt leased for dispatch"
        );
        traceEventLogger.taskWorkAttemptStatusTransition(
                task.getTid(),
                work.messageId(),
                attemptId,
                attemptNo,
                work.workerId(),
                work.workerContextId(),
                work.batchId(),
                null,
                AttemptStatus.LEASED,
                AttemptStatus.DISPATCHED,
                "BIND_TASK_MESSAGE",
                "SimpleTaskDispatchBinder",
                "attempt dispatched"
        );
        return new TaskDispatchBinding(
                task.getTid(),
                work.messageId(),
                work.eventCode(),
                work.payload(),
                work.payloadRef(),
                work.retryCount(),
                attemptId,
                attemptNo,
                work.leaseToken(),
                work.workerId(),
                work.workerContextId(),
                work.batchId()
        );
    }

    private boolean prepareWorkerContextForDispatch(Task task, WorkerContext workerContext) {
        if (workerContext == null) {
            return true;
        }

        boolean changed = false;
        String taskId = task.getTid();
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            WorkerContextStatus fromStatus = workerContext.getStatus();
            if (!workerContext.bindToTask(taskId)) {
                return false;
            }
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    "SimpleTaskDispatchBinder",
                    "workerContext reserved for task"
            );
            changed = true;
        }
        if (workerContext.getStatus() == WorkerContextStatus.RESERVED
                && taskId.equals(workerContext.getLastBindTaskId())) {
            WorkerContextStatus fromStatus = workerContext.getStatus();
            if (!workerContext.startOccupying()) {
                return false;
            }
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    "SimpleTaskDispatchBinder",
                    "workerContext advanced to occupied"
            );
            changed = true;
        }

        boolean alreadySendingForTask = workerContext.getStatus() == WorkerContextStatus.OCCUPIED
                && taskId.equals(workerContext.getLastBindTaskId());
        if (!alreadySendingForTask && workerContext.getStatus() != WorkerContextStatus.OCCUPIED) {
            return false;
        }

        return !changed || workerManager.updateWorkerContextById(workerContext.getWorkerContextId(), workerContext);
    }

    private void releaseAssignedWorkerContexts(Task task, List<DispatchSlot> dispatchSlots) {
        for (DispatchSlot slot : dispatchSlots) {
            if (slot.assignedCount() <= 0) {
                continue;
            }
            releaseWorkerContextIfIdleForTask(task, slot.workerContext(),
                    "workerContext released after dispatch submit failure");
        }
    }

    private void releaseWorkerContextIfIdleForTask(Task task, WorkerContext workerContext, String reason) {
        if (task == null || workerContext == null) {
            return;
        }
        if (workerContext.getLastBindTaskId() != null && !task.getTid().equals(workerContext.getLastBindTaskId())) {
            return;
        }
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            return;
        }
        WorkerContextStatus fromStatus = workerContext.getStatus();
        if (!workerContext.release()) {
            log.warn("[MsgAssign] WorkerContext {} could not be released for task {} from status {} ({})",
                    workerContext.getWorkerContextId(), task.getTid(), workerContext.getStatus(), reason);
            return;
        }
        boolean stored = workerManager.updateWorkerContextById(workerContext.getWorkerContextId(), workerContext);
        if (!stored) {
            log.warn("[MsgAssign] Failed to persist workerContext {} release for task {} ({})",
                    workerContext.getWorkerContextId(), task.getTid(), reason);
        }
        traceEventLogger.workerContextStatusTransition(
                task.getTid(),
                workerContext,
                fromStatus,
                workerContext.getStatus(),
                "RELEASE_WORKER_CONTEXT",
                "SimpleTaskDispatchBinder",
                reason
        );
    }

    private java.util.Set<String> supportedEventCodes(Worker worker) {
        if (worker == null || worker.getSupportedEventCodes() == null || worker.getSupportedEventCodes().isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.LinkedHashSet<>(worker.getSupportedEventCodes());
    }
}


