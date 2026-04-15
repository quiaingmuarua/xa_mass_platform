package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public List<TaskMsg> onMsgAssign(Task task, List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched workers were provided", task.getTid());
            return List.of();
        }

        List<TaskMsg> pendingMessages = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(this::isPendingDispatch)
                .collect(Collectors.toList());
        int totalMessages = pendingMessages.size();
        if (totalMessages == 0) {
            log.info("[MsgAssign] Skip task {} because there are no pending task messages to dispatch", task.getTid());
            return List.of();
        }

        int perWorkerBatchLimit = Math.max(task.getBatchSize(), 1);
        int batchId = 0;
        int cursor = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();
        List<DispatchSlot> dispatchSlots = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} workers, totalMessages={}, perWorkerBatchLimit={}",
                task.getTid(), workers.size(), totalMessages, perWorkerBatchLimit);

        for (int i = 0; i < workers.size() && cursor < totalMessages; i++) {
            Worker worker = workers.get(i);
            String currentBatchId = "batch-" + batchId;
            WorkerContext workerContext = workerManager.getWorkerContext(worker.getWorkerId());
            if (!prepareWorkerContextForDispatch(task, worker, workerContext)) {
                log.warn("[MsgAssign] Skip worker {} for task {} because workerContext state is not dispatchable",
                        worker.getWorkerId(), task.getTid());
                workerManager.unlockWorker(worker.getWorkerId());
                batchId++;
                continue;
            }
            String workerContextId = workerContext != null ? workerContext.getWorkerContextId() : null;
            dispatchSlots.add(new DispatchSlot(worker, workerContext, workerContextId, currentBatchId));
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
            }
        }

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
        private final String workerContextId;
        private final String batchId;
        private int assignedCount;

        private DispatchSlot(Worker worker, WorkerContext workerContext, String workerContextId, String batchId) {
            this.worker = worker;
            this.workerContext = workerContext;
            this.workerContextId = workerContextId;
            this.batchId = batchId;
        }

        private Worker worker() {
            return worker;
        }

        private WorkerContext workerContext() {
            return workerContext;
        }

        private String workerContextId() {
            return workerContextId;
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
        if (!taskMsg.transitionTo(TaskMsgStatus.BINDING)) {
            return false;
        }
        taskMsg.setWorkerId(workerId);
        taskMsg.setWorkerContextId(workerContextId);
        taskMsg.setBatchId(batchId);
        return taskMsg.markAsSent();
    }

    private boolean prepareWorkerContextForDispatch(Task task, Worker worker, WorkerContext workerContext) {
        if (workerContext == null) {
            return true;
        }

        boolean changed = false;
        String taskId = task.getTid();
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            if (!workerContext.bindToTask(taskId)) {
                return false;
            }
            changed = true;
        }
        if (workerContext.getStatus() == WorkerContextStatus.RESERVED && taskId.equals(workerContext.getLastBindTaskId())) {
            if (!workerContext.startOccupying()) {
                return false;
            }
            changed = true;
        }

        boolean alreadySendingForTask = workerContext.getStatus() == WorkerContextStatus.OCCUPIED
                && taskId.equals(workerContext.getLastBindTaskId());
        if (!alreadySendingForTask && workerContext.getStatus() != WorkerContextStatus.OCCUPIED) {
            return false;
        }

        return !changed || workerManager.updateWorkerContext(worker.getWorkerId(), workerContext);
    }
}
