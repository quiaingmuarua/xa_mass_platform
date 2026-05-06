package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
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
    private final TaskAssignmentRuntimePort assignmentRuntime;
    private final TaskAssignmentEventSink assignmentEventSink;
    private final TraceEventLogger traceEventLogger;

    public TaskWorkerAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(ruleManager, workerManager, msgAssignListener, recordService, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this(new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService, traceEventLogger),
                workerManager, msgAssignListener, assignmentRuntime, assignmentEventSink, traceEventLogger);
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink) {
        this(matchingStrategy, workerManager, msgAssignListener, assignmentRuntime, assignmentEventSink, TraceEventLogger.noop());
    }

    public TaskWorkerAssignListener(TaskWorkerMatchingStrategy matchingStrategy,
                                    WorkerManager workerManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    TaskAssignmentRuntimePort assignmentRuntime,
                                    TaskAssignmentEventSink assignmentEventSink,
                                    TraceEventLogger traceEventLogger) {
        this.matchingStrategy = matchingStrategy;
        this.workerManager = workerManager;
        this.msgAssignListener = msgAssignListener;
        this.assignmentRuntime = assignmentRuntime;
        this.assignmentEventSink = assignmentEventSink;
        this.traceEventLogger = traceEventLogger;
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

        int pendingDispatchCount = assignmentRuntime.countPendingDispatchableMessages(task.getTid());
        if (pendingDispatchCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no pending INIT task messages", null);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, 0, 0, 0,
                    0, 0, 0, 0,
                    "no pending INIT task messages", "SKIPPED");
            return false;
        }

        int desiredDispatchWorkerCount = getDesiredDispatchWorkerCount(task, pendingDispatchCount);
        int requiredStartWorkerCount = initialStatus == TaskStatus.READY
                ? getRequiredStartWorkerCount(task)
                : 1;
        int matchRequestCount = Math.max(requiredStartWorkerCount, desiredDispatchWorkerCount);
        List<MatchedWorkerContext> matched = matchingStrategy.matchWorkers(task, matchRequestCount);
        log.info("[WorkerAssign] Strategy {} matched {} worker-context candidates for task {}",
                matchingStrategy.getClass().getSimpleName(), matched.size(), task.getTid());
        if (matched.isEmpty()) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no matched worker-context candidates", requiredStartWorkerCount);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    0, 0, 0, 0,
                    "no matched worker-context candidates", "SKIPPED");
            return false;
        }
        if (initialStatus == TaskStatus.READY && matched.size() < requiredStartWorkerCount) {
            log.info("[WorkerAssign] Keep task {} in READY because matched workers {} are below required minimum {}",
                    task.getTid(), matched.size(), requiredStartWorkerCount);
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "matched workers below minimum start gate", requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "matched workers below minimum start gate", "SKIPPED");
            return false;
        }
        if (task.getStatus() != initialStatus) {
            log.info("[WorkerAssign] Skip dispatch for task {} because status changed from {} to {} during matching",
                    task.getTid(), initialStatus, task.getStatus());
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "task status changed during matching from " + initialStatus + " to " + task.getStatus(),
                    requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "task status changed during matching from " + initialStatus + " to " + task.getStatus(), "SKIPPED");
            return false;
        }

        List<MatchedWorkerContext> dispatchCandidates = matched.subList(0, Math.min(matched.size(), desiredDispatchWorkerCount));
        if (dispatchCandidates.isEmpty()) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "no dispatch candidates remained after capacity trim", requiredStartWorkerCount);
            unlockWorkers(matched);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), 0, 0, 0,
                    "no dispatch candidates remained after capacity trim", "SKIPPED");
            return false;
        }
        unlockWorkers(matched.subList(dispatchCandidates.size(), matched.size()));

        List<TaskDispatchBinding> dispatchedBindings = msgAssignListener.onMsgAssign(task, List.copyOf(dispatchCandidates));
        long usedWorkerCount = dispatchedBindings.stream()
                .map(TaskDispatchBinding::attempt)
                .map(TaskMsgAttempt::getWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
        if (usedWorkerCount <= 0) {
            traceEventLogger.dispatchSkipped(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                    "matched candidates produced no bound task messages", requiredStartWorkerCount);
            unlockWorkers(dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
                    matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), 0,
                    "matched candidates produced no bound task messages", "SKIPPED");
            return false;
        }

            traceEventLogger.dispatchRequested(task, "ON_TASK_ASSIGN", "TaskWorkerAssignListener",
                "matched candidates produced dispatchable task messages");

        task.setPeakAssignedWorkerCount(Math.max(task.getPeakAssignedWorkerCount(), (int) usedWorkerCount));
        if (initialStatus == TaskStatus.READY && !task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[WorkerAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            unlockWorkers(dispatchCandidates);
            emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                    requiredStartWorkerCount, matchRequestCount,
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
        emitAssignmentSummary(task, initialStatus, pendingDispatchCount, desiredDispatchWorkerCount,
                requiredStartWorkerCount, matchRequestCount,
                matched.size(), dispatchCandidates.size(), dispatchedBindings.size(), (int) usedWorkerCount,
                "matched workers dispatched", "SUCCESS");
        assignmentRuntime.updateTask(task);
        assignmentEventSink.publishTaskAssigned(task);
        return true;
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
            traceEventLogger.workerLockReleased(null, workerId, "UNLOCK_WORKER", "TaskWorkerAssignListener",
                    "surplus or skipped dispatch candidate");
        }
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
