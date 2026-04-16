package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
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
    private final TaskMsgAssignListener msgAssignListener;
    private final TaskManager taskManager;

    public TaskWorkerAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService,
                                    TaskManager taskManager) {
        this(new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService),
                workerManager, msgAssignListener, taskManager);
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    TaskManager taskManager) {
        this.matchingStrategy = matchingStrategy;
        this.workerManager = workerManager;
        this.msgAssignListener = msgAssignListener;
        this.taskManager = taskManager;
    }

    /**
     * Processes a task assignment attempt.
     */
    public boolean onTaskAssign(Task task) {
        TaskStatus initialStatus = task.getStatus();
        if (initialStatus != TaskStatus.READY && initialStatus != TaskStatus.RUNNING) {
            return false;
        }

        int pendingDispatchCount = taskManager.countPendingDispatchableMessages(task.getTid());
        if (pendingDispatchCount <= 0) {
            return false;
        }

        int desiredDispatchWorkerCount = getDesiredDispatchWorkerCount(task, pendingDispatchCount);
        int requiredStartWorkerCount = initialStatus == TaskStatus.READY
                ? getRequiredStartWorkerCount(task)
                : 1;
        int matchRequestCount = Math.max(requiredStartWorkerCount, desiredDispatchWorkerCount);
        List<MatchedWorkerContext> matched = matchWorkersWithRules(task, matchRequestCount);
        if (matched.isEmpty()) {
            return false;
        }
        if (initialStatus == TaskStatus.READY && matched.size() < requiredStartWorkerCount) {
            log.info("[WorkerAssign] Keep task {} in READY because matched workers {} are below required minimum {}",
                    task.getTid(), matched.size(), requiredStartWorkerCount);
            unlockWorkers(matched);
            return false;
        }
        if (task.getStatus() != initialStatus) {
            log.info("[WorkerAssign] Skip dispatch for task {} because status changed from {} to {} during matching",
                    task.getTid(), initialStatus, task.getStatus());
            unlockWorkers(matched);
            return false;
        }

        List<MatchedWorkerContext> dispatchCandidates = matched.subList(0, Math.min(matched.size(), desiredDispatchWorkerCount));
        if (dispatchCandidates.isEmpty()) {
            unlockWorkers(matched);
            return false;
        }
        unlockWorkers(matched.subList(dispatchCandidates.size(), matched.size()));

        List<TaskMsg> dispatchedMessages = msgAssignListener.onMsgAssign(task, List.copyOf(dispatchCandidates));
        long usedWorkerCount = dispatchedMessages.stream()
                .map(TaskMsg::getWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        if (usedWorkerCount <= 0) {
            return false;
        }

        task.setPeakAssignedWorkerCount(Math.max(task.getPeakAssignedWorkerCount(), (int) usedWorkerCount));
        if (initialStatus == TaskStatus.READY && !task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[WorkerAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            unlockWorkers(dispatchCandidates);
            return false;
        }
        if (initialStatus == TaskStatus.READY) {
            TraceEventLogger.taskStatusTransition(
                    task.getTid(),
                    initialStatus,
                    task.getStatus(),
                    "ASSIGNMENT_SUCCEEDED",
                    "TaskWorkerAssignListener",
                    "matched workers dispatched"
            );
        }
        taskManager.updateTask(task);
        return true;
    }

    /**
     * Kept for compatibility with current tests and callers; the implementation is now strategy-based.
     */
    List<MatchedWorkerContext> matchWorkersWithRules(Task task, int maxWorkerCount) {
        List<MatchedWorkerContext> matchedWorkers = matchingStrategy.matchWorkers(task, maxWorkerCount);
        log.info("[WorkerAssign] Strategy {} matched {} worker-context candidates for task {}",
                matchingStrategy.getClass().getSimpleName(), matchedWorkers.size(), task.getTid());
        return matchedWorkers;
    }

    private int getDesiredDispatchWorkerCount(Task task, int pendingDispatchCount) {
        int remainingMessages = Math.max(pendingDispatchCount, 1);
        return Math.max(1, (int) Math.ceil((double) remainingMessages / task.getBatchSize()));
    }

    private int getRequiredStartWorkerCount(Task task) {
        return Math.max(task.getMinRequiredWorkerCount(), 1);
    }

    private void unlockWorkers(List<MatchedWorkerContext> workers) {
        for (String workerId : workers.stream()
                .map(MatchedWorkerContext::getWorkerId)
                .distinct()
                .collect(Collectors.toList())) {
            workerManager.unlockWorker(workerId);
            TraceEventLogger.workerLockReleased(null, workerId, "UNLOCK_WORKER", "TaskWorkerAssignListener",
                    "surplus or skipped dispatch candidate");
        }
    }
}
