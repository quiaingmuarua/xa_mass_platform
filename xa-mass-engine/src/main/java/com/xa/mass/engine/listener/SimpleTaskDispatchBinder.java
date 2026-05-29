package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptStatus;
import com.xa.mass.engine.TaskWorkAttemptIdSupport;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.runtime.TaskRuntimeClaimOptionsResolver;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claims runtime-ready work for matched workers and emits the dispatch queue.
 */
public class SimpleTaskDispatchBinder implements TaskDispatchBinder {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskDispatchBinder.class);
    private static final TaskRuntimeClaimOptionsResolver TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER =
            new TaskRuntimeClaimOptionsResolver();

    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final WorkerAdmissionRuntime workerAdmissionRuntime;
    private final AssignmentDiagnosticRecorder recordService;
    private final TaskDispatchBatchListener dispatchListener;
    private final TraceEventLogger traceEventLogger;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerDispatchResourceReleaser resourceReleaser;

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerAdmissionRuntime workerAdmissionRuntime,
                                       AssignmentDiagnosticRecorder recordService) {
        this(assignmentRuntime, workerAdmissionRuntime, recordService, null, TraceEventLogger.noop());
    }

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerAdmissionRuntime workerAdmissionRuntime,
                                       AssignmentDiagnosticRecorder recordService,
                                       TaskDispatchBatchListener dispatchListener) {
        this(assignmentRuntime, workerAdmissionRuntime, recordService, dispatchListener, TraceEventLogger.noop());
    }

    public SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerAdmissionRuntime workerAdmissionRuntime,
                                       AssignmentDiagnosticRecorder recordService,
                                       TaskDispatchBatchListener dispatchListener,
                                       TraceEventLogger traceEventLogger) {
        this(assignmentRuntime, workerAdmissionRuntime, recordService, dispatchListener, traceEventLogger,
                new DefaultWorkerDispatchResourcePolicy());
    }

    SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                             WorkerAdmissionRuntime workerAdmissionRuntime,
                             AssignmentDiagnosticRecorder recordService,
                             TaskDispatchBatchListener dispatchListener,
                             TraceEventLogger traceEventLogger,
                             WorkerDispatchResourcePolicy resourcePolicy) {
        this(assignmentRuntime, workerAdmissionRuntime, recordService, dispatchListener, traceEventLogger,
                resourcePolicy, null);
    }

    SimpleTaskDispatchBinder(TaskAssignmentRuntimePort assignmentRuntime,
                             WorkerAdmissionRuntime workerAdmissionRuntime,
                             AssignmentDiagnosticRecorder recordService,
                             TaskDispatchBatchListener dispatchListener,
                             TraceEventLogger traceEventLogger,
                             WorkerDispatchResourcePolicy resourcePolicy,
                             WorkerDispatchResourceReleaser resourceReleaser) {
        this.assignmentRuntime = assignmentRuntime;
        this.workerAdmissionRuntime = workerAdmissionRuntime;
        this.recordService = recordService;
        this.dispatchListener = dispatchListener;
        this.traceEventLogger = traceEventLogger;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.resourceReleaser = resourceReleaser == null
                ? new WorkerDispatchResourceReleaser(workerAdmissionRuntime, this.resourcePolicy, traceEventLogger)
                : resourceReleaser;
    }

    @Override
    public List<TaskDispatchBinding> bindDispatches(Task task, List<WorkerSchedulingCandidate> matchedWorkers) {
        if (matchedWorkers == null || matchedWorkers.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched worker scheduling candidates were provided", task.getTid());
            traceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Math.max(task.getExecutionSpec().getBatchSize(), 1),
                    "ON_MSG_ASSIGN",
                    "SimpleTaskDispatchBinder",
                    "no matched worker scheduling candidates were provided",
                    "SKIPPED"
            );
            return List.of();
        }

        int readyWorkCount = assignmentRuntime.countDispatchReadyWork(task.getTid());
        if (readyWorkCount == 0) {
            log.info("[MsgAssign] Skip task {} because there is no runtime-ready work to dispatch", task.getTid());
            resourceReleaser.releaseReservations(task, matchedWorkers);
            traceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    matchedWorkers.size(),
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
            WorkerSchedulingCandidate matchedWorker = matchedWorkers.get(i);
            dispatchSlots.add(new DispatchSlot(matchedWorker));
        }

        List<WorkerClaimTarget> claimTargets = dispatchSlots.stream()
                .map(slot -> WorkerClaimTarget.workerLevel(
                        slot.workerId(),
                        slot.batchId(),
                        perWorkerBatchLimit,
                        supportedEventCodes(slot.candidate)
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
            TaskDispatchBinding dispatchBinding = bindClaimedTaskWork(task, work, slot);
            dispatchBindings.add(dispatchBinding);
            if (!workerAdmissionRuntime.confirmWorkerReservation(work.workerId(), task.getTid())) {
                workerAdmissionRuntime.recordWorkClaimed(work.workerId(), task.getTid());
            }
            slot.incrementAssigned();

            recordService.recordMessageAssignment(
                    task, slot.candidate, work.messageId(), slot.batchId(),
                    AssignmentResult.SUCCESS, "message assigned",
                    workerAdmissionRuntime.hasWorkerExclusiveLease(slot.workerId())
            );
        }

        for (DispatchSlot slot : dispatchSlots) {
            if (slot.assignedCount() == 0) {
                resourceReleaser.releaseReservationAndLock(task, slot.candidate,
                        "UNLOCK_WORKER", "SimpleTaskDispatchBinder", "matched worker received no messages");
            }
        }

        int uniqueWorkerCount = (int) dispatchBindings.stream()
                .map(TaskDispatchBinding::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        traceEventLogger.dispatchBindingSummary(
                task,
                readyWorkCount,
                matchedWorkers.size(),
                dispatchSlots.size(),
                dispatchBindings.size(),
                uniqueWorkerCount,
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
                if (!compensated) {
                    throw new IllegalStateException("dispatch submit compensation failed for task " + task.getTid(), e);
                }
                releaseObservedWorkerLoad(immutableDispatchBindings);
                releaseAssignedWorkerLocks(task, dispatchSlots,
                        "dispatch submit failed before transport delivery");
                traceEventLogger.dispatchBindingSummary(
                        task,
                        readyWorkCount,
                        matchedWorkers.size(),
                        dispatchSlots.size(),
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

    private void releaseObservedWorkerLoad(List<TaskDispatchBinding> dispatchBindings) {
        if (dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        for (TaskDispatchBinding binding : dispatchBindings) {
            workerAdmissionRuntime.recordWorkFinal(binding.workerId(), binding.taskId());
        }
    }

    private static final class DispatchSlot {
        private final WorkerSchedulingCandidate candidate;
        private final String batchId = java.util.UUID.randomUUID().toString();
        private int assignedCount;

        private DispatchSlot(WorkerSchedulingCandidate candidate) {
            this.candidate = candidate;
        }

        private String workerId() {
            return candidate.getWorkerId();
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
            if (slot.workerId().equals(workerId) && slot.batchId().equals(batchId)) {
                return slot;
            }
        }
        return null;
    }

    private TaskDispatchBinding bindClaimedTaskWork(Task task, ClaimedTaskWork work, DispatchSlot slot) {
        int attemptNo = Math.max(0, work.retryCount()) + 1;
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                work.messageId(),
                attemptNo,
                work.workerId(),
                work.batchId()
        );
        WorkerSchedulingView schedulingView = slot.candidate.getSchedulingView();
        String eventBindingKey = eventBindingKey(task, work.eventCode());
        String workerCandidateSource = workerCandidateSource(task);
        Map<String, Object> dispatchEvidence = dispatchEvidence(schedulingView, eventBindingKey, workerCandidateSource);
        traceEventLogger.taskWorkAttemptStatusTransition(
                task.getTid(),
                work.messageId(),
                attemptId,
                attemptNo,
                work.workerId(),
                work.batchId(),
                null,
                AttemptStatus.CREATED,
                AttemptStatus.LEASED,
                "BIND_TASK_MESSAGE",
                "SimpleTaskDispatchBinder",
                "attempt leased for dispatch",
                dispatchEvidence
        );
        traceEventLogger.taskWorkAttemptStatusTransition(
                task.getTid(),
                work.messageId(),
                attemptId,
                attemptNo,
                work.workerId(),
                work.batchId(),
                null,
                AttemptStatus.LEASED,
                AttemptStatus.DISPATCHED,
                "BIND_TASK_MESSAGE",
                "SimpleTaskDispatchBinder",
                "attempt dispatched",
                dispatchEvidence
        );
        return TaskDispatchBinding.workerLevelWithEvidence(
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
                work.batchId(),
                schedulingView.workerGroupId(),
                schedulingView.adapterNodeId(),
                eventBindingKey,
                workerCandidateSource
        );
    }

    private static Map<String, Object> dispatchEvidence(WorkerSchedulingView schedulingView,
                                                        String eventBindingKey,
                                                        String workerCandidateSource) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (schedulingView != null) {
            evidence.put("workerGroupId", schedulingView.workerGroupId());
            evidence.put("adapterNodeId", schedulingView.adapterNodeId());
        }
        evidence.put("eventBindingKey", eventBindingKey);
        evidence.put("workerCandidateSource", workerCandidateSource);
        return evidence;
    }

    private static String eventBindingKey(Task task, String eventCode) {
        if (task == null || task.getProject() == null || task.getProject().isBlank()
                || eventCode == null || eventCode.isBlank()) {
            return null;
        }
        return task.getProject().trim() + ":" + eventCode.trim();
    }

    private static String workerCandidateSource(Task task) {
        if (task == null) {
            return null;
        }
        if (TaskSharedConfig.workerGroupSelector(task).isEmpty()) {
            return null;
        }
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return "TARGET_WORKER";
        }
        String adapterNodeId = TaskSharedConfig.adapterNodeId(task);
        if (adapterNodeId != null && !adapterNodeId.isBlank()) {
            return "GROUP_SELECTOR_WITH_NODE";
        }
        return "GROUP_SELECTOR";
    }

    private void releaseAssignedWorkerLocks(Task task, List<DispatchSlot> dispatchSlots, String reason) {
        List<WorkerSchedulingCandidate> assignedCandidates = dispatchSlots.stream()
                .filter(slot -> slot.assignedCount() > 0)
                .map(slot -> slot.candidate)
                .toList();
        resourceReleaser.releaseLocks(
                task,
                assignedCandidates,
                "UNLOCK_WORKER",
                "SimpleTaskDispatchBinder",
                reason
        );
    }

    private java.util.Set<String> supportedEventCodes(WorkerSchedulingCandidate candidate) {
        if (candidate == null || candidate.getSchedulingView().supportedEventCodes().isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.LinkedHashSet<>(candidate.getSchedulingView().supportedEventCodes());
    }
}
