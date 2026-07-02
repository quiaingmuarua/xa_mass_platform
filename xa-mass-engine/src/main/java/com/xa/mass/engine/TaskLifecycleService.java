package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns task-level lifecycle transitions that are not callback-result specific.
 */
class TaskLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(TaskLifecycleService.class);
    private static final String TRACE_SOURCE = TaskLifecycleService.class.getSimpleName();

    private final Function<String, Task> taskReader;
    private final Predicate<Task> taskWriter;
    private final Consumer<Task> runtimeSchedulerEligibilitySync;
    private final Consumer<Task> taskReadyPublisher;
    private final Consumer<Task> taskTerminalPublisher;
    private final Function<String, TaskRuntimeProgressSnapshot> runtimeProgressReader;
    private final BiFunction<Task, TaskRuntimeProgressSnapshot, TaskTerminalPolicyDecision> terminalPolicyEvaluator;
    private final BiConsumer<Task, List<RuntimeTaskIngressItem>> runtimeIngressAppender;
    private final BiConsumer<Task, Integer> runtimeAppendAdmissionValidator;
    private final Consumer<Task> dispatchRequester;
    private final Consumer<String> taskProgressUpdater;
    private final Predicate<String> taskRecordDeleter;
    private final Consumer<String> runtimeDiscarder;
    private final Consumer<String> workDiscarder;
    private final TraceEventLogger traceEventLogger;
    private final int maxIngestBatchItems;

    TaskLifecycleService(Function<String, Task> taskReader,
                         Predicate<Task> taskWriter,
                         Consumer<Task> runtimeSchedulerEligibilitySync,
                         Consumer<Task> taskReadyPublisher,
                         Consumer<Task> taskTerminalPublisher,
                         Function<String, TaskRuntimeProgressSnapshot> runtimeProgressReader,
                         BiFunction<Task, TaskRuntimeProgressSnapshot, TaskTerminalPolicyDecision> terminalPolicyEvaluator,
                         BiConsumer<Task, List<RuntimeTaskIngressItem>> runtimeIngressAppender,
                         BiConsumer<Task, Integer> runtimeAppendAdmissionValidator,
                         Consumer<Task> dispatchRequester,
                         Consumer<String> taskProgressUpdater,
                         Predicate<String> taskRecordDeleter,
                         Consumer<String> runtimeDiscarder,
                         Consumer<String> workDiscarder,
                         TraceEventLogger traceEventLogger,
                         int maxIngestBatchItems) {
        this.taskReader = taskReader;
        this.taskWriter = taskWriter;
        this.runtimeSchedulerEligibilitySync = runtimeSchedulerEligibilitySync;
        this.taskReadyPublisher = taskReadyPublisher;
        this.taskTerminalPublisher = taskTerminalPublisher;
        this.runtimeProgressReader = runtimeProgressReader;
        this.terminalPolicyEvaluator = terminalPolicyEvaluator;
        this.runtimeIngressAppender = runtimeIngressAppender;
        this.runtimeAppendAdmissionValidator = runtimeAppendAdmissionValidator;
        this.dispatchRequester = dispatchRequester;
        this.taskProgressUpdater = taskProgressUpdater;
        this.taskRecordDeleter = taskRecordDeleter;
        this.runtimeDiscarder = runtimeDiscarder;
        this.workDiscarder = workDiscarder;
        this.traceEventLogger = traceEventLogger;
        this.maxIngestBatchItems = maxIngestBatchItems;
    }

    TaskCommandOutcome approveTask(String taskId) {
        return transitionTask(
                taskId,
                "APPROVE_TASK",
                "TASK_APPROVE",
                task -> task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING,
                "task already approved",
                "TASK_ALREADY_APPROVED",
                "Task is already approved",
                task -> task.getStatus() == TaskStatus.NEW || task.getStatus() == TaskStatus.BLOCKED,
                task -> {
                    boolean result = task.transitionTo(TaskStatus.READY);
                    if (result) {
                        task.setHoldReason(null);
                    }
                    return result;
                },
                this::publishTaskReady,
                "task approved",
                "task approved",
                "TASK_APPROVED",
                "Task approved",
                "TASK_NOT_APPROVABLE",
                "Task status is not approvable",
                "Failed to approve task");
    }

    TaskCommandOutcome rejectTask(String taskId) {
        return transitionTask(
                taskId,
                "REJECT_TASK",
                "TASK_REJECT",
                task -> task.getStatus() == TaskStatus.BLOCKED
                        && task.getHoldReason() == TaskHoldReason.REVIEW_REJECTED,
                "task already rejected",
                "TASK_ALREADY_REJECTED",
                "Task is already rejected",
                task -> task.getStatus() == TaskStatus.NEW,
                task -> task.transitionToBlocked(TaskHoldReason.REVIEW_REJECTED),
                task -> { },
                "task rejected",
                "task rejected and moved to BLOCKED",
                "TASK_REJECTED",
                "Task rejected",
                "TASK_NOT_REJECTABLE",
                "Task status is not rejectable",
                "Failed to reject task");
    }

    TaskCommandOutcome blockTask(String taskId) {
        return transitionTask(
                taskId,
                "BLOCK_TASK",
                "TASK_BLOCK",
                task -> task.getStatus() == TaskStatus.BLOCKED,
                "task already blocked",
                "TASK_ALREADY_BLOCKED",
                "Task is already blocked",
                task -> task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING,
                task -> task.transitionToBlocked(TaskHoldReason.MANUAL_BLOCKED),
                task -> { },
                "task blocked",
                "task blocked",
                "TASK_BLOCKED",
                "Task blocked",
                "TASK_NOT_BLOCKABLE",
                "Task status is not blockable",
                "Failed to block task");
    }

    TaskCommandOutcome pauseTask(String taskId) {
        return transitionTask(
                taskId,
                "PAUSE_TASK",
                "TASK_PAUSE",
                task -> task.getStatus() == TaskStatus.PAUSED,
                "task already paused",
                "TASK_ALREADY_PAUSED",
                "Task is already paused",
                task -> task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING,
                task -> {
                    boolean result = task.transitionTo(TaskStatus.PAUSED);
                    if (result) {
                        task.setHoldReason(null);
                    }
                    return result;
                },
                task -> { },
                "task paused",
                "task paused",
                "TASK_PAUSED",
                "Task paused",
                "TASK_NOT_PAUSABLE",
                "Task status is not pausable",
                "Failed to pause task");
    }

    private TaskCommandOutcome transitionTask(String taskId,
                                              String operation,
                                              String failurePrefix,
                                              Predicate<Task> alreadyApplied,
                                              String alreadyLogMessage,
                                              String alreadyReasonCode,
                                              String alreadyMessage,
                                              Predicate<Task> canApply,
                                              Function<Task, Boolean> transition,
                                              Consumer<Task> afterApply,
                                              String traceMessage,
                                              String successLogMessage,
                                              String appliedReasonCode,
                                              String appliedMessage,
                                              String rejectedReasonCode,
                                              String rejectedMessage,
                                              String exceptionLogMessage) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart(operation, TRACE_SOURCE, "taskId", taskId);

        try {
            Task task = loadTask(taskId);
            if (task == null) {
                LogUtils.logOperationFailure(failurePrefix + "_ERROR", "task not found",
                        System.currentTimeMillis() - startTime);
                return TaskCommandOutcome.notFound(taskId);
            }
            if (alreadyApplied.test(task)) {
                LogUtils.logOperationSuccess(alreadyLogMessage, System.currentTimeMillis() - startTime);
                return TaskCommandOutcome.alreadyApplied(taskId, alreadyReasonCode, alreadyMessage);
            }
            if (!canApply.test(task)) {
                LogUtils.logOperationFailure(failurePrefix + "_ERROR", rejectedMessage,
                        System.currentTimeMillis() - startTime);
                return TaskCommandOutcome.rejected(taskId, rejectedReasonCode, rejectedMessage);
            }

            TaskStatus fromStatus = task.getStatus();
            if (!transition.apply(task)) {
                LogUtils.logOperationFailure(failurePrefix + "_ERROR", "task status transition failed",
                        System.currentTimeMillis() - startTime);
                return TaskCommandOutcome.conflict(
                        taskId, failurePrefix + "_CONFLICT", "Task status transition failed");
            }

            traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                    operation, TRACE_SOURCE, traceMessage);
            storeTask(task);
            syncRuntimeSchedulerEligibility(task);
            afterApply.accept(task);
            LogUtils.logOperationSuccess(successLogMessage, System.currentTimeMillis() - startTime);
            return TaskCommandOutcome.applied(taskId, appliedReasonCode, appliedMessage);
        } catch (Exception e) {
            LogUtils.logOperationFailure(failurePrefix + "_ERROR", e.getMessage(),
                    System.currentTimeMillis() - startTime);
            logger.error(exceptionLogMessage, e);
            return TaskCommandOutcome.rejected(taskId, failurePrefix + "_FAILED", e.getMessage());
        }
    }

    TaskCommandOutcome resumeTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("RESUME_TASK", TRACE_SOURCE, "taskId", taskId);

        try {
            Task task = loadTask(taskId);
            if (task == null) {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task not found", duration);
                return TaskCommandOutcome.notFound(taskId);
            }
            if (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING) {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationSuccess("task already resumed", duration);
                return TaskCommandOutcome.alreadyApplied(taskId, "TASK_ALREADY_RESUMED", "Task is already active");
            }
            if (task.getStatus() == TaskStatus.PAUSED) {
                TaskRuntimeProgressSnapshot stats = readRuntimeProgress(taskId);
                TaskTerminalPolicyDecision decision = evaluateTerminalPolicy(task, stats);
                if (decision.getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
                    task.setTaskSuccessNumber((int) Math.min(stats.successCount(), Integer.MAX_VALUE));
                    TaskTerminalReason terminalReason = decision.getTerminalReason();
                    TaskStatus fromStatus = task.getStatus();
                    boolean result = task.transitionTo(TaskStatus.TERMINAL, terminalReason);
                    if (result) {
                        traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                                "RESUME_TASK", TRACE_SOURCE, "task already completed while paused");
                        traceEventLogger.taskTerminalClosed(taskId, fromStatus, terminalReason,
                                "RESUME_TASK", TRACE_SOURCE, "task already completed while paused");
                        storeTask(task);
                        syncRuntimeSchedulerEligibility(task);
                        publishTaskTerminal(task);
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationSuccess("task completed while paused and closed to TERMINAL", duration);
                        return TaskCommandOutcome.applied(taskId, "TASK_RESUMED_TO_TERMINAL",
                                "Task completed while paused and closed to TERMINAL");
                    }
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task was complete but terminal closure failed", duration);
                    return TaskCommandOutcome.conflict(taskId, "TASK_RESUME_TERMINAL_CONFLICT",
                            "Task was complete but terminal closure failed");
                }
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    task.setHoldReason(null);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "RESUME_TASK", TRACE_SOURCE, "task resumed to ready");
                    storeTask(task);
                    syncRuntimeSchedulerEligibility(task);
                    publishTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task resumed to READY", duration);
                    return TaskCommandOutcome.applied(taskId, "TASK_RESUMED_TO_READY", "Task resumed to READY");
                }
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task status transition failed", duration);
                return TaskCommandOutcome.conflict(taskId, "TASK_RESUME_CONFLICT", "Task status transition failed");
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task not found or status is not resumable", duration);
            return TaskCommandOutcome.rejected(taskId, "TASK_NOT_RESUMABLE", "Task status is not resumable");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", e.getMessage(), duration);
            logger.error("Failed to resume task", e);
            return TaskCommandOutcome.rejected(taskId, "TASK_RESUME_FAILED", e.getMessage());
        }
    }

    TaskCommandOutcome cancelTask(String taskId) {
        return doTerminateTask(taskId, TaskTerminalReason.MANUAL_CANCELLED, "CANCEL_TASK");
    }

    TaskCommandOutcome terminateTask(String taskId, TaskTerminalReason reason) {
        return doTerminateTask(taskId, reason, "TERMINATE_TASK");
    }

    TaskAppendOutcome appendTaskItems(String taskId, List<java.util.Map<String, Object>> items) {
        Task task = loadTask(taskId);
        if (task == null) {
            return TaskAppendOutcome.rejected(taskId, "TASK_NOT_FOUND", "Task not found");
        }
        if (items == null || items.isEmpty()) {
            return TaskAppendOutcome.rejected(taskId, "APPEND_EMPTY_BATCH", "items must be a non-empty list");
        }
        if (items.size() > maxIngestBatchItems) {
            return TaskAppendOutcome.rejected(taskId, "APPEND_BATCH_TOO_LARGE",
                    "append items exceed ingest batch limit: " + items.size() + " > " + maxIngestBatchItems);
        }
        if (!canAcceptTaskItems(task)) {
            return TaskAppendOutcome.rejected(taskId, "APPEND_INTAKE_CLOSED", describeItemAppendRejection(task, taskId));
        }

        List<RuntimeTaskIngressItem> ingressItems = new java.util.ArrayList<>(items.size());
        for (java.util.Map<String, Object> item : items) {
            ingressItems.add(RuntimeTaskIngressItem.fromInput(
                    taskId,
                    java.util.UUID.randomUUID().toString(),
                    item,
                    task.getExecutionSpec().getDefaultMaxRetryCount()
            ));
        }
        validateAtomicAppendAdmission(task, addedItemCount(ingressItems));
        appendRuntimeIngressItems(task, ingressItems);
        int added = ingressItems.size();
        List<String> messageIds = ingressItems.stream()
                .map(RuntimeTaskIngressItem::messageId)
                .toList();
        task.setTaskTargetNumber(task.getTaskTargetNumber() + added);
        task.setTaskEligibleNumber(task.getTaskEligibleNumber() + added);
        storeTask(task);
        if (task.getStatus().isActive()) {
            requestTaskDispatch(task);
        }
        logger.info("[appendTaskItems] Added {} items to task {}", added, taskId);
        return TaskAppendOutcome.accepted(taskId, added, messageIds);
    }

    private void validateAtomicAppendAdmission(Task task, int itemCount) {
        runtimeAppendAdmissionValidator.accept(task, itemCount);
    }

    private int addedItemCount(List<RuntimeTaskIngressItem> ingressItems) {
        return ingressItems == null ? 0 : ingressItems.size();
    }

    TaskCommandOutcome sealTask(String taskId) {
        Task task = loadTask(taskId);
        if (task == null) {
            return TaskCommandOutcome.notFound(taskId);
        }
        if (!task.isIntakeOpen()) {
            return TaskCommandOutcome.alreadyApplied(taskId, "TASK_INTAKE_ALREADY_CLOSED",
                    "Task intake is already closed");
        }
        task.sealIntake();
        storeTask(task);
        updateTaskProgress(taskId);
        logger.info("[sealTask] Sealed task {}", taskId);
        return TaskCommandOutcome.applied(taskId, "TASK_INTAKE_SEALED", "Task intake sealed");
    }

    private boolean canAcceptTaskItems(Task task) {
        return task.getStatus() != null
                && !task.getStatus().isFinal()
                && task.isIntakeOpen();
    }

    private String describeItemAppendRejection(Task task, String taskId) {
        if (task.isIntakeSealed()) {
            return "Task intake is sealed: " + taskId;
        }
        return "Task intake is closed or task is terminal: " + task.getStatus();
    }

    TaskCommandOutcome deleteTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("DELETE_TASK", TRACE_SOURCE, "taskId", taskId);

        Task task = loadTask(taskId);
        if (task == null) {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task not found", 0);
            return TaskCommandOutcome.notFound(taskId);
        }
        TaskStatus status = task.getStatus();
        if (status != TaskStatus.NEW && status != TaskStatus.TERMINAL) {
            logger.warn("Refusing to delete non-terminal task: taskId={}, status={}", taskId, status);
            LogUtils.logOperationFailure("TASK_DELETE_REJECTED",
                    "task status " + status + " is not deletable; terminate it first", 0);
            return TaskCommandOutcome.rejected(taskId, "TASK_NOT_DELETABLE",
                    "task status " + status + " is not deletable; terminate it first");
        }

        boolean result = deleteTaskRecord(taskId);
        if (result) {
            discardTaskRuntime(taskId);
            LogUtils.logOperationSuccess("task deleted", 0);
            return TaskCommandOutcome.applied(taskId, "TASK_DELETED", "Task deleted");
        } else {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task deletion failed", 0);
        }
        return TaskCommandOutcome.conflict(taskId, "TASK_DELETE_FAILED", "Task deletion failed");
    }

    private TaskCommandOutcome doTerminateTask(String taskId, TaskTerminalReason reason, String trigger) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart(trigger, TRACE_SOURCE, "taskId", taskId, "reason", reason.name());

        try {
            Task task = loadTask(taskId);
            if (task == null) {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure(trigger + "_ERROR", "task not found", duration);
                return TaskCommandOutcome.notFound(taskId);
            }
            if (task.getStatus().isFinal()) {
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationSuccess("task already terminal", duration);
                return TaskCommandOutcome.alreadyApplied(taskId, "TASK_ALREADY_TERMINAL", "Task is already terminal");
            }
            if (!task.getStatus().isFinal()) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
                if (result) {
                    closeTaskIntakeOnTerminal(task);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            trigger, TRACE_SOURCE, "task terminated: " + reason);
                    traceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                            trigger, TRACE_SOURCE, "task terminated: " + reason);
                    storeTask(task);
                    syncRuntimeSchedulerEligibility(task);
                    publishTaskTerminal(task);
                    discardTaskWork(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task terminated: " + reason, duration);
                    return TaskCommandOutcome.applied(taskId, "TASK_TERMINATED", "Task terminated: " + reason);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure(trigger + "_ERROR", "task status transition failed", duration);
                }
                return TaskCommandOutcome.conflict(taskId, "TASK_TERMINATE_CONFLICT", "Task status transition failed");
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure(trigger + "_ERROR", "task not found or already terminal", duration);
            return TaskCommandOutcome.rejected(taskId, "TASK_NOT_TERMINABLE", "Task is not terminable");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure(trigger + "_ERROR", e.getMessage(), duration);
            logger.error("Failed to terminate task {}", taskId, e);
            return TaskCommandOutcome.rejected(taskId, "TASK_TERMINATE_FAILED", e.getMessage());
        }
    }

    private Task loadTask(String taskId) {
        return taskReader.apply(taskId);
    }

    private void storeTask(Task task) {
        taskWriter.test(task);
    }

    private void syncRuntimeSchedulerEligibility(Task task) {
        runtimeSchedulerEligibilitySync.accept(task);
    }

    private void publishTaskReady(Task task) {
        taskReadyPublisher.accept(task);
    }

    private void publishTaskTerminal(Task task) {
        taskTerminalPublisher.accept(task);
    }

    private TaskRuntimeProgressSnapshot readRuntimeProgress(String taskId) {
        return runtimeProgressReader.apply(taskId);
    }

    private TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats) {
        return terminalPolicyEvaluator.apply(task, stats);
    }

    private void appendRuntimeIngressItems(Task task, List<RuntimeTaskIngressItem> ingressItems) {
        runtimeIngressAppender.accept(task, ingressItems);
    }

    private void requestTaskDispatch(Task task) {
        dispatchRequester.accept(task);
    }

    private void updateTaskProgress(String taskId) {
        taskProgressUpdater.accept(taskId);
    }

    private boolean deleteTaskRecord(String taskId) {
        return taskRecordDeleter.test(taskId);
    }

    private void discardTaskRuntime(String taskId) {
        runtimeDiscarder.accept(taskId);
    }

    private void discardTaskWork(String taskId) {
        workDiscarder.accept(taskId);
    }

    private void closeTaskIntakeOnTerminal(Task task) {
        if (task == null) {
            return;
        }
        task.sealIntake();
    }
}
