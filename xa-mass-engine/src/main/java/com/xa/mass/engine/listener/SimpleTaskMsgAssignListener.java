package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
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

        List<TaskMsg> pendingMessages = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(this::isPendingDispatch)
                .collect(Collectors.toList());
        int totalMessages = pendingMessages.size();
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
        int batchId = 0;
        int cursor = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();
        List<DispatchSlot> dispatchSlots = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} matched candidates, totalMessages={}, perWorkerBatchLimit={}",
                task.getTid(), matchedWorkers.size(), totalMessages, perWorkerBatchLimit);

        for (int i = 0; i < matchedWorkers.size() && cursor < totalMessages; i++) {
            MatchedWorkerContext matchedWorker = matchedWorkers.get(i);
            Worker worker = matchedWorker.getWorker();
            WorkerContext workerContext = matchedWorker.getWorkerContext();
            String currentBatchId = "batch-" + batchId;
            if (!prepareWorkerContextForDispatch(task, workerContext)) {
                log.warn("[MsgAssign] Skip worker {} context {} for task {} because workerContext state is not dispatchable",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        task.getTid());
                workerManager.unlockWorker(worker.getWorkerId());
                TraceEventLogger.workerLockReleased(task.getTid(), worker.getWorkerId(),
                        "UNLOCK_WORKER", "SimpleTaskMsgAssignListener", "workerContext not dispatchable");
                batchId++;
                continue;
            }
            dispatchSlots.add(new DispatchSlot(worker, workerContext, currentBatchId));
            batchId++;
        }

        while (cursor < totalMessages) {
            boolean assignedInRound = false;
            for (DispatchSlot slot : dispatchSlots) {
                if (!slot.canAccept(perWorkerBatchLimit) || cursor >= totalMessages) {
                    continue;
                }
                TaskMsg msg = pendingMessages.get(cursor);
                if (!bindTaskMessage(msg, slot.worker().getWorkerId(), slot.workerContextId(), slot.batchId())) {
                    log.warn("[MsgAssign] Skip task message {} because it could not transition from status {}",
                            msg.getMsgId(), msg.getStatus());
                    cursor++;
                    continue;
                }
                cursor++;
                taskManager.updateTaskMessage(task.getTid(), msg);
                pushQueue.add(msg);
                slot.incrementAssigned();
                assignedInRound = true;

                recordService.recordMessageAssignment(
                        task, slot.worker(), slot.workerContext(), msg.getMsgId(), slot.batchId(),
                        AssignmentResult.SUCCESS, "message assigned",
                        workerManager.isLocked(slot.worker().getWorkerId())
                );
            }
            if (!assignedInRound) {
                break;
            }
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
            dispatchListener.onTaskMsgsReady(task, List.copyOf(pushQueue));
        }
        return List.copyOf(pushQueue);
    }

    private static final class DispatchSlot {
        private final Worker worker;
        private final WorkerContext workerContext;
        private final String batchId;
        private int assignedCount;

        private DispatchSlot(Worker worker, WorkerContext workerContext, String batchId) {
            this.worker = worker;
            this.workerContext = workerContext;
            this.batchId = batchId;
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

    private boolean bindTaskMessage(TaskMsg taskMsg, String workerId, String workerContextId, String batchId) {
        taskMsg.applyLatestAttemptProjection(workerId, workerContextId, batchId);
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                java.util.UUID.randomUUID().toString(),
                taskMsg.getTaskId(),
                taskMsg.getMsgId(),
                taskManager.getTaskMessageAttempts(taskMsg.getTaskId(), taskMsg.getMsgId()).size() + 1
        );
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        TaskMsgAttemptStatus initialAttemptStatus = attempt.getStatus();
        if (!attempt.markLeased(LocalDateTime.now().plusMinutes(5))) {
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
        taskManager.addTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMsgId(), attempt);

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
