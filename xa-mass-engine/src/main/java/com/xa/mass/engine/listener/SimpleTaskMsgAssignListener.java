package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.*;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.runtime.TaskRuntimeClaimOptionsResolver;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.work.ClaimedTaskWork;
import com.xa.mass.engine.work.TaskWorkClaimOptions;
import com.xa.mass.engine.work.WorkerClaimTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Binds persisted task messages to matched workers and emits the dispatch queue.
 */
public class SimpleTaskMsgAssignListener implements TaskMsgAssignListener {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskMsgAssignListener.class);
    private static final TaskRuntimeClaimOptionsResolver TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER =
            new TaskRuntimeClaimOptionsResolver();

    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final WorkerManager workerManager;
    private final AssignmentRecordService recordService;
    private final TaskMsgDispatchListener dispatchListener;

    public SimpleTaskMsgAssignListener(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerManager workerManager,
                                       AssignmentRecordService recordService) {
        this(assignmentRuntime, workerManager, recordService, null);
    }

    public SimpleTaskMsgAssignListener(TaskAssignmentRuntimePort assignmentRuntime,
                                       WorkerManager workerManager,
                                       AssignmentRecordService recordService,
                                       TaskMsgDispatchListener dispatchListener) {
        this.assignmentRuntime = assignmentRuntime;
        this.workerManager = workerManager;
        this.recordService = recordService;
        this.dispatchListener = dispatchListener;
    }

    @Override
    public List<TaskDispatchBinding> onMsgAssign(Task task, List<MatchedWorkerContext> matchedWorkers) {
        if (matchedWorkers == null || matchedWorkers.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched worker-context candidates were provided", task.getTid());
            TraceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Math.max(task.getBatchSize(), 1),
                    "ON_MSG_ASSIGN",
                    "SimpleTaskMsgAssignListener",
                    "no matched worker-context candidates were provided",
                    "SKIPPED"
            );
            return List.of();
        }

        int totalMessages = assignmentRuntime.countPendingDispatchableMessages(task.getTid());
        if (totalMessages == 0) {
            log.info("[MsgAssign] Skip task {} because there are no pending task messages to dispatch", task.getTid());
            TraceEventLogger.dispatchBindingSummary(
                    task,
                    0,
                    matchedWorkers.size(),
                    0,
                    0,
                    0,
                    0,
                    Math.max(task.getBatchSize(), 1),
                    "ON_MSG_ASSIGN",
                    "SimpleTaskMsgAssignListener",
                    "there are no pending task messages to dispatch",
                    "SKIPPED"
            );
            return List.of();
        }

        int resolvedWorkerCount = Math.max(matchedWorkers.size(), 1);
        TaskWorkClaimOptions claimOptions = TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER.resolve(
                task,
                resolvedWorkerCount,
                assignmentRuntime.getTaskMessageLeaseSeconds()
        );
        int perWorkerBatchLimit = claimOptions.perWorkerCapacity();
        List<TaskDispatchBinding> dispatchBindings = new ArrayList<>();
        List<DispatchSlot> dispatchSlots = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} matched candidates, totalMessages={}, perWorkerBatchLimit={}",
                task.getTid(), matchedWorkers.size(), totalMessages, perWorkerBatchLimit);

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
                TraceEventLogger.workerLockReleased(task.getTid(), worker.getWorkerId(),
                        "UNLOCK_WORKER", "SimpleTaskMsgAssignListener", "workerContext not dispatchable");
                continue;
            }
            dispatchSlots.add(new DispatchSlot(worker, workerContext));
        }

        List<WorkerClaimTarget> claimTargets = dispatchSlots.stream()
                .map(slot -> new WorkerClaimTarget(
                        slot.worker().getWorkerId(),
                        slot.workerContextId(),
                        slot.batchId(),
                        perWorkerBatchLimit
                ))
                .collect(Collectors.toList());
        claimOptions = TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER.resolve(
                task,
                Math.max(dispatchSlots.size(), 1),
                assignmentRuntime.getTaskMessageLeaseSeconds()
        );
        List<ClaimedTaskWork> claimed = assignmentRuntime.getTaskWorkRuntime()
                .claimReady(task.getTid(), claimTargets, claimOptions);

        for (ClaimedTaskWork work : claimed) {
            DispatchSlot slot = findSlot(dispatchSlots, work.workerId(), work.batchId());
            if (slot == null) {
                log.warn("[MsgAssign] Skip claimed work {} because dispatch slot was not found", work.messageId());
                continue;
            }
            TaskMsg msg = assignmentRuntime.getTaskMessage(task.getTid(), work.messageId());
            if (msg == null) {
                log.warn("[MsgAssign] Skip claimed work {} because task message was not found", work.messageId());
                continue;
            }
            TaskDispatchBinding dispatchBinding = bindTaskMessage(msg, work);
            if (dispatchBinding == null) {
                log.warn("[MsgAssign] Skip task message {} because it could not transition from status {}",
                        msg.getMessageId(), msg.getStatus());
                continue;
            }
            assignmentRuntime.updateTaskMessage(task.getTid(), msg);
            dispatchBindings.add(dispatchBinding);
            slot.incrementAssigned();

            recordService.recordMessageAssignment(
                    task, slot.worker(), slot.workerContext(), msg.getMessageId(), slot.batchId(),
                    AssignmentResult.SUCCESS, "message assigned",
                    workerManager.isLocked(slot.worker().getWorkerId())
            );
        }

        for (DispatchSlot slot : dispatchSlots) {
            if (slot.assignedCount() == 0) {
                workerManager.unlockWorker(slot.worker().getWorkerId());
                TraceEventLogger.workerLockReleased(task.getTid(), slot.worker().getWorkerId(),
                        "UNLOCK_WORKER", "SimpleTaskMsgAssignListener", "matched worker received no messages");
            }
        }

        int uniqueWorkerCount = (int) dispatchBindings.stream()
                .map(TaskDispatchBinding::attempt)
                .map(TaskMsgAttempt::getWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        int uniqueWorkerContextCount = (int) dispatchBindings.stream()
                .map(TaskDispatchBinding::attempt)
                .map(TaskMsgAttempt::getWorkerContextId)
                .filter(workerContextId -> workerContextId != null && !workerContextId.isBlank())
                .distinct()
                .count();
        TraceEventLogger.dispatchBindingSummary(
                task,
                totalMessages,
                matchedWorkers.size(),
                dispatchSlots.size(),
                dispatchBindings.size(),
                uniqueWorkerCount,
                uniqueWorkerContextCount,
                perWorkerBatchLimit,
                "ON_MSG_ASSIGN",
                "SimpleTaskMsgAssignListener",
                dispatchBindings.isEmpty()
                        ? "matched workers produced no dispatchable bindings"
                        : "task messages bound to dispatch slots",
                dispatchBindings.isEmpty() ? "SKIPPED" : "SUCCESS"
        );

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected pending={})",
                task.getTid(), dispatchBindings.size(), totalMessages);

        if (dispatchListener != null && !dispatchBindings.isEmpty()) {
            dispatchListener.onTaskMsgsReady(task, List.copyOf(dispatchBindings));
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

        private boolean canAccept(int perWorkerBatchLimit) {
            return assignedCount < perWorkerBatchLimit;
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

    private TaskDispatchBinding bindTaskMessage(TaskMsg taskMsg, ClaimedTaskWork work) {
        TaskMsgAttempt latestAttempt = assignmentRuntime.getLatestTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                java.util.UUID.randomUUID().toString(),
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                latestAttempt != null ? latestAttempt.getAttemptNo() + 1 : 1
        );
        attempt.setWorkerId(work.workerId());
        attempt.setWorkerContextId(work.workerContextId());
        attempt.setBatchId(work.batchId());
        taskMsg.applyLatestAttemptProjection(attempt.getAttemptId(), work.workerId(), work.workerContextId(), work.batchId());
        TaskMsgAttemptStatus initialAttemptStatus = attempt.getStatus();
        LocalDateTime leaseExpireTime = LocalDateTime.ofInstant(work.leaseExpireAt(), ZoneId.systemDefault());
        if (!attempt.markLeased(leaseExpireTime)) {
            return null;
        }
        TraceEventLogger.taskMsgAttemptStatusTransition(
                attempt,
                initialAttemptStatus,
                attempt.getStatus(),
                "BIND_TASK_MESSAGE",
                "SimpleTaskMsgAssignListener",
                "attempt leased for dispatch"
        );
        TaskMsgAttemptStatus beforeDispatch = attempt.getStatus();
        if (!attempt.markDispatched()) {
            return null;
        }
        TraceEventLogger.taskMsgAttemptStatusTransition(
                attempt,
                beforeDispatch,
                attempt.getStatus(),
                "BIND_TASK_MESSAGE",
                "SimpleTaskMsgAssignListener",
                "attempt dispatched"
        );
        assignmentRuntime.addTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId(), attempt);

        TaskMsgStatus beforeAssigned = taskMsg.getStatus();
        if (!taskMsg.markAsAssigned()) {
            return null;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                attempt,
                beforeAssigned,
                taskMsg.getStatus(),
                "BIND_TASK_MESSAGE",
                "SimpleTaskMsgAssignListener",
                "task message assigned to worker"
        );
        return new TaskDispatchBinding(taskMsg, attempt);
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
            TraceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    "SimpleTaskMsgAssignListener",
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
            TraceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    "SimpleTaskMsgAssignListener",
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
}
