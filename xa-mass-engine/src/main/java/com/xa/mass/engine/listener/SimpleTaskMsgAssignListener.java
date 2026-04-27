package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.*;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.work.ClaimedTaskWork;
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

    private final TaskManager taskManager;
    private final WorkerManager workerManager;
    private final AssignmentRecordService recordService;
    private final TaskMsgDispatchListener dispatchListener;

    public SimpleTaskMsgAssignListener(TaskManager taskManager,
                                       WorkerManager workerManager,
                                       AssignmentRecordService recordService) {
        this(taskManager, workerManager, recordService, null);
    }

    public SimpleTaskMsgAssignListener(TaskManager taskManager,
                                       WorkerManager workerManager,
                                       AssignmentRecordService recordService,
                                       TaskMsgDispatchListener dispatchListener) {
        this.taskManager = taskManager;
        this.workerManager = workerManager;
        this.recordService = recordService;
        this.dispatchListener = dispatchListener;
    }

    @Override
    public List<TaskMsg> onMsgAssign(Task task, List<MatchedWorkerContext> matchedWorkers) {
        if (matchedWorkers == null || matchedWorkers.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched worker-context candidates were provided", task.getTid());
            TraceEventLogger.dispatchBindingSummary(
                    task.getTid(),
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

        int totalMessages = taskManager.countPendingDispatchableMessages(task.getTid());
        if (totalMessages == 0) {
            log.info("[MsgAssign] Skip task {} because there are no pending task messages to dispatch", task.getTid());
            TraceEventLogger.dispatchBindingSummary(
                    task.getTid(),
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

        int perWorkerBatchLimit = Math.max(task.getBatchSize(), 1);
        List<TaskMsg> pushQueue = new ArrayList<>();
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
        int maxItems = Math.min(totalMessages, dispatchSlots.size() * perWorkerBatchLimit);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime()
                .claimReady(task.getTid(), claimTargets, maxItems, taskManager.getTaskMessageLeaseSeconds());

        for (ClaimedTaskWork work : claimed) {
            DispatchSlot slot = findSlot(dispatchSlots, work.workerId(), work.batchId());
            if (slot == null) {
                log.warn("[MsgAssign] Skip claimed work {} because dispatch slot was not found", work.messageId());
                continue;
            }
            TaskMsg msg = taskManager.getTaskMessage(task.getTid(), work.messageId());
            if (msg == null) {
                log.warn("[MsgAssign] Skip claimed work {} because task message was not found", work.messageId());
                continue;
            }
            if (!bindTaskMessage(msg, work)) {
                log.warn("[MsgAssign] Skip task message {} because it could not transition from status {}",
                        msg.getMessageId(), msg.getStatus());
                continue;
            }
            taskManager.updateTaskMessage(task.getTid(), msg);
            TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), msg.getMessageId());
            if (latestAttempt == null) {
                log.warn("[MsgAssign] Skip dispatch callback for task message {} because latest attempt is missing after bind",
                        msg.getMessageId());
                continue;
            }
            pushQueue.add(msg);
            dispatchBindings.add(new TaskDispatchBinding(msg, latestAttempt));
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

        int uniqueWorkerCount = (int) pushQueue.stream()
                .map(TaskMsg::getLatestAttemptWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        int uniqueWorkerContextCount = (int) pushQueue.stream()
                .map(TaskMsg::getLatestAttemptWorkerContextId)
                .filter(workerContextId -> workerContextId != null && !workerContextId.isBlank())
                .distinct()
                .count();
        TraceEventLogger.dispatchBindingSummary(
                task.getTid(),
                totalMessages,
                matchedWorkers.size(),
                dispatchSlots.size(),
                pushQueue.size(),
                uniqueWorkerCount,
                uniqueWorkerContextCount,
                perWorkerBatchLimit,
                "ON_MSG_ASSIGN",
                "SimpleTaskMsgAssignListener",
                pushQueue.isEmpty()
                        ? "matched workers produced no dispatchable bindings"
                        : "task messages bound to dispatch slots",
                pushQueue.isEmpty() ? "SKIPPED" : "SUCCESS"
        );

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected pending={})",
                task.getTid(), pushQueue.size(), totalMessages);

        if (dispatchListener != null && !pushQueue.isEmpty()) {
            dispatchListener.onTaskMsgsReady(task, List.copyOf(dispatchBindings));
        }
        return List.copyOf(pushQueue);
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

    private boolean isPendingDispatch(TaskMsg taskMsg) {
        return taskMsg != null && taskMsg.getStatus() == TaskMsgStatus.INIT;
    }

    private DispatchSlot findSlot(List<DispatchSlot> dispatchSlots, String workerId, String batchId) {
        for (DispatchSlot slot : dispatchSlots) {
            if (slot.worker().getWorkerId().equals(workerId) && slot.batchId().equals(batchId)) {
                return slot;
            }
        }
        return null;
    }

    private boolean bindTaskMessage(TaskMsg taskMsg, ClaimedTaskWork work) {
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
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
            return false;
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
            return false;
        }
        TraceEventLogger.taskMsgAttemptStatusTransition(
                attempt,
                beforeDispatch,
                attempt.getStatus(),
                "BIND_TASK_MESSAGE",
                "SimpleTaskMsgAssignListener",
                "attempt dispatched"
        );
        taskManager.addTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId(), attempt);

        TaskMsgStatus beforeAssigned = taskMsg.getStatus();
        if (!taskMsg.markAsAssigned()) {
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                beforeAssigned,
                taskMsg.getStatus(),
                "BIND_TASK_MESSAGE",
                "SimpleTaskMsgAssignListener",
                "task message assigned to worker"
        );
        return true;
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
