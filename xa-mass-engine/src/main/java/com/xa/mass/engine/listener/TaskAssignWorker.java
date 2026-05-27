package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lane-aware assignment signal worker.
 *
 * <p>This component owns session/interactive assignment signals and keeps the
 * task-level matching plus runtime work-claim model, while separating signals
 * into workload lanes so interactive dispatch is not forced back into a
 * single global queue.
 */
public class TaskAssignWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignWorker.class);
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 1000L;
    static final int DEFAULT_ASSIGNMENT_QUEUE_CAPACITY =
            Integer.getInteger("xa.mass.engine.assignmentQueueCapacity", 10_000);
    private static final TaskRuntimeProfileResolver TASK_RUNTIME_PROFILE_RESOLVER =
            new TaskRuntimeProfileResolver();

    private final TaskWorkerAssignListener workerAssignListener;
    private final long retryDelayMillis;
    private final int assignmentQueueCapacity;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TaskRuntimeProfileResolver taskRuntimeProfileResolver;
    private final TraceEventLogger traceEventLogger;
    private final Map<TaskRuntimeProfile.DispatchLane, LaneState> laneStates =
            new EnumMap<>(TaskRuntimeProfile.DispatchLane.class);
    private final List<TaskAssignmentQueueListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Set<String> trackedTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deferredRequeueTaskIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, WaitingRetry> waitingRetriesByTaskId = new ConcurrentHashMap<>();
    private final AtomicLong signalSequence = new AtomicLong();

    private volatile boolean running = true;

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener) {
        this(workerAssignListener, DEFAULT_RETRY_DELAY_MILLIS, TraceEventLogger.noop());
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener, TraceEventLogger traceEventLogger) {
        this(workerAssignListener, DEFAULT_RETRY_DELAY_MILLIS, traceEventLogger);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener, long retryDelayMillis) {
        this(workerAssignListener, retryDelayMillis, TraceEventLogger.noop());
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener, long retryDelayMillis, TraceEventLogger traceEventLogger) {
        this(workerAssignListener, retryDelayMillis, DEFAULT_ASSIGNMENT_QUEUE_CAPACITY, traceEventLogger);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                            long retryDelayMillis,
                            int assignmentQueueCapacity) {
        this(workerAssignListener, retryDelayMillis, assignmentQueueCapacity, TraceEventLogger.noop());
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                            long retryDelayMillis,
                            int assignmentQueueCapacity,
                            TraceEventLogger traceEventLogger) {
        this(workerAssignListener,
                retryDelayMillis,
                assignmentQueueCapacity,
                new TaskRuntimeRetryPolicyResolver(),
                TASK_RUNTIME_PROFILE_RESOLVER,
                traceEventLogger);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                            long retryDelayMillis,
                            int assignmentQueueCapacity,
                            TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver) {
        this(workerAssignListener,
                retryDelayMillis,
                assignmentQueueCapacity,
                taskRuntimeRetryPolicyResolver,
                TASK_RUNTIME_PROFILE_RESOLVER,
                TraceEventLogger.noop());
    }

    TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                     long retryDelayMillis,
                     int assignmentQueueCapacity,
                     TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                     TaskRuntimeProfileResolver taskRuntimeProfileResolver,
                     TraceEventLogger traceEventLogger) {
        this.workerAssignListener = workerAssignListener;
        this.retryDelayMillis = retryDelayMillis;
        this.assignmentQueueCapacity = Math.max(1, assignmentQueueCapacity);
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
        this.taskRuntimeProfileResolver = taskRuntimeProfileResolver != null
                ? taskRuntimeProfileResolver
                : TASK_RUNTIME_PROFILE_RESOLVER;
        this.traceEventLogger = traceEventLogger;
    }

    TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                     long retryDelayMillis,
                     int assignmentQueueCapacity,
                     TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                     TraceEventLogger traceEventLogger) {
        this(workerAssignListener,
                retryDelayMillis,
                assignmentQueueCapacity,
                taskRuntimeRetryPolicyResolver,
                TASK_RUNTIME_PROFILE_RESOLVER,
                traceEventLogger);
    }

    public void addAssignmentQueueListener(TaskAssignmentQueueListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void start() {
        running = true;
        laneStates.clear();
        for (TaskRuntimeProfile.DispatchLane lane : TaskRuntimeProfile.DispatchLane.values()) {
            LaneState laneState = new LaneState(lane, assignmentQueueCapacity);
            laneState.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TaskAssignWorker-" + lane.name());
                t.setDaemon(true);
                return t;
            });
            laneState.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TaskAssignWorkerRetry-" + lane.name());
                t.setDaemon(true);
                return t;
            });
            laneStates.put(lane, laneState);
            laneState.executor.submit(() -> drainLane(laneState));
        }
    }

    public CompletableFuture<Void> submitAll(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        pendingTasks.addAndGet(tasks.size());
        for (Task task : tasks) {
            SubmitResult submitResult = submit(task, true);
            if (submitResult.isHardRejected()) {
                completeRejectedBatchSubmission(task);
            }
        }

        // Use the common pool instead of `executor`: the task-processing loop already occupies
        // the single thread in `executor`, so submitting this polling future to the same
        // executor would queue it behind a loop that never exits, causing a deadlock.
        return CompletableFuture.runAsync(() -> {
            while (pendingTasks.get() > 0 && running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public boolean submit(Task task) {
        return submitDetailed(task).acceptsSignal();
    }

    public SubmitResult submitDetailed(Task task) {
        return submit(task, false);
    }

    /**
     * Re-enqueues only tasks already known to this owner as waiting for lane retry.
     *
     * <p>This is the bounded lane-side wakeup used by worker availability changes.
     * It does not scan task storage or discover READY tasks.</p>
     */
    public int wakeWaitingRetries(String reason) {
        if (!running || waitingRetriesByTaskId.isEmpty()) {
            return 0;
        }
        int woken = 0;
        for (WaitingRetry waitingRetry : List.copyOf(waitingRetriesByTaskId.values())) {
            if (runWaitingRetry(waitingRetry, true, reason)) {
                woken++;
            }
        }
        return woken;
    }

    public void stop() {
        running = false;
        trackedTaskIds.clear();
        deferredRequeueTaskIds.clear();
        waitingRetriesByTaskId.clear();
        emitQueueSnapshot(null, null, null, "STOPPING", null,
                "assignment signal worker is stopping", "SUCCESS");
        for (LaneState laneState : laneStates.values()) {
            if (laneState.retryExecutor != null) {
                laneState.retryExecutor.shutdownNow();
            }
            if (laneState.executor != null) {
                laneState.executor.shutdownNow();
            }
        }
        for (LaneState laneState : laneStates.values()) {
            try {
                if (laneState.retryExecutor != null
                        && !laneState.retryExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("TaskAssignWorker retry executor {} did not terminate within 10 seconds",
                            laneState.lane.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TaskAssignWorker retry executor {} to stop",
                        laneState.lane.name());
            }
            try {
                if (laneState.executor != null
                        && !laneState.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("TaskAssignWorker executor {} did not terminate within 10 seconds",
                            laneState.lane.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TaskAssignWorker executor {} to stop",
                        laneState.lane.name());
            }
        }
    }

    private void drainLane(LaneState laneState) {
        while (running) {
            Task task = null;
            try {
                TaskAssignmentSignal signal = laneState.take();
                task = signal.task();
                String taskId = task != null ? task.getTid() : null;
                TaskStatus initialStatus = task != null ? task.getStatus() : null;
                if (initialStatus == TaskStatus.READY || initialStatus == TaskStatus.RUNNING) {
                    boolean assigned = workerAssignListener.onTaskAssign(task);
                    if (running && !assigned && task.getStatus() == initialStatus) {
                        scheduleRetry(task, initialStatus, laneState);
                    } else if (!enqueueDeferredRequeueIfRequested(task)) {
                        releaseTrackedTask(taskId);
                        notifyAssignmentProcessed(task, laneState);
                    } else {
                        emitQueueSnapshot(task, task.getStatus(), laneState, "REQUEUE_ENQUEUED", null,
                                "deferred requeue requested while assignment was still processing", "SUCCESS");
                    }
                } else {
                    clearDeferredRequeue(taskId);
                    releaseTrackedTask(taskId);
                    emitQueueSnapshot(task, initialStatus, laneState, "SKIPPED_NON_DISPATCHABLE", null,
                            "task skipped because status is not READY or RUNNING", "SKIPPED");
                    notifyAssignmentProcessed(task, laneState);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                clearDeferredRequeue(task != null ? task.getTid() : null);
                releaseTrackedTask(task != null ? task.getTid() : null);
                log.error("TaskAssignWorker error on lane {}: {}", laneState.lane.name(), e.getMessage(), e);
            }
        }
    }

    private void emitQueueSnapshot(Task task,
                                   TaskStatus taskStatus,
                                   LaneState laneState,
                                   String queueAction,
                                   Long retryDelayMillis,
                                   String reason,
                                   String result) {
        traceEventLogger.assignmentQueueSnapshot(
                task,
                taskStatus,
                laneState != null ? laneState.lane.name() : resolveDispatchLane(task).name(),
                laneState != null ? laneState.queueDepth() : totalQueueDepth(),
                pendingTasks.get(),
                laneState != null ? laneState.scheduledRetryCount.get() : totalScheduledRetryCount(),
                queueAction,
                retryDelayMillis,
                queueAction,
                "TaskAssignWorker",
                reason,
                result
        );
    }

    private boolean trackTask(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return true;
        }
        return trackedTaskIds.add(task.getTid());
    }

    private boolean markDeferredRequeue(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return false;
        }
        TaskStatus status = task.getStatus();
        if (status != TaskStatus.RUNNING) {
            return false;
        }
        return deferredRequeueTaskIds.add(task.getTid());
    }

    private boolean enqueueDeferredRequeueIfRequested(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return false;
        }
        String taskId = task.getTid();
        LaneState laneState = resolveLaneState(task);
        if (!deferredRequeueTaskIds.remove(taskId)) {
            return false;
        }
        TaskStatus status = task.getStatus();
        if (!running || (status != TaskStatus.READY && status != TaskStatus.RUNNING)) {
            releaseTrackedTask(taskId);
            emitQueueSnapshot(task, status, laneState, "REQUEUE_DROPPED", null,
                    "deferred requeue was dropped because task is no longer dispatchable", "SKIPPED");
            notifyAssignmentProcessed(task, laneState);
            return false;
        }
        if (!enqueueSignal(task, AssignmentSignalReason.REQUEUE, laneState)) {
            releaseTrackedTask(taskId);
            emitQueueSnapshot(task, status, laneState, "REQUEUE_QUEUE_FULL", null,
                    "assignment signal queue is full; deferred requeue was dropped", "REJECTED");
            notifyAssignmentProcessed(task, laneState);
            return false;
        }
        return true;
    }

    private void clearDeferredRequeue(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            deferredRequeueTaskIds.remove(taskId);
        }
    }

    private void releaseTrackedTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            trackedTaskIds.remove(taskId);
        }
    }

    private boolean enqueueSignal(Task task, AssignmentSignalReason reason, LaneState laneState) {
        if (task == null || !running || laneState == null) {
            return false;
        }
        TaskRuntimeProfile profile = taskRuntimeProfileResolver.resolve(task);
        return laneState.offer(new TaskAssignmentSignal(
                task,
                reason,
                profile.dispatchPriority().ordinal(),
                signalSequence.getAndIncrement()
        ));
    }

    private void scheduleSubmissionRetry(Task task, LaneState laneState) {
        if (task == null || laneState == null || laneState.retryExecutor == null) {
            releaseTrackedTask(task != null ? task.getTid() : null);
            return;
        }
        TaskRuntimeRetryPolicy retryPolicy = taskRuntimeRetryPolicyResolver.resolve(task, retryDelayMillis);
        long resolvedRetryDelayMillis = retryPolicy.assignmentRetryDelayMillis();
        laneState.scheduledRetryCount.incrementAndGet();
        emitQueueSnapshot(task, task.getStatus(), laneState, "SUBMIT_RETRY_SCHEDULED", resolvedRetryDelayMillis,
                "assignment signal queue is full; submit will be retried", "DEFERRED");
        laneState.retryExecutor.schedule(() -> {
            laneState.scheduledRetryCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            TaskStatus currentStatus = task.getStatus();
            if (!running) {
                releaseTrackedTask(task.getTid());
                completeUnprocessedTrackedTask(task, laneState, "RETRY_DROPPED",
                        "submit retry was dropped because assignment signal worker is stopping", "SKIPPED");
                return;
            }
            if (currentStatus != TaskStatus.READY && currentStatus != TaskStatus.RUNNING) {
                releaseTrackedTask(task.getTid());
                completeUnprocessedTrackedTask(task, laneState, "RETRY_DROPPED",
                        "submit retry was dropped because task is no longer dispatchable", "SKIPPED");
                return;
            }
            if (enqueueSignal(task, AssignmentSignalReason.RETRY, laneState)) {
                emitQueueSnapshot(task, currentStatus, laneState, "RETRY_ENQUEUED", resolvedRetryDelayMillis,
                        "delayed submit retry enqueued task back into assignment signal queue", "SUCCESS");
                return;
            }
            emitQueueSnapshot(task, currentStatus, laneState, "RETRY_QUEUE_FULL", resolvedRetryDelayMillis,
                    "assignment signal queue is still full; submit retry will be rescheduled", "DEFERRED");
            scheduleSubmissionRetry(task, laneState);
        }, resolvedRetryDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleRetry(Task task, TaskStatus expectedStatus, LaneState laneState) {
        if (laneState == null || laneState.retryExecutor == null) {
            return;
        }
        TaskRuntimeRetryPolicy retryPolicy = taskRuntimeRetryPolicyResolver.resolve(task, retryDelayMillis);
        long resolvedRetryDelayMillis = retryPolicy.assignmentRetryDelayMillis();
        laneState.scheduledRetryCount.incrementAndGet();
        WaitingRetry waitingRetry = new WaitingRetry(task, expectedStatus, laneState, resolvedRetryDelayMillis);
        WaitingRetry previous = waitingRetriesByTaskId.put(task.getTid(), waitingRetry);
        if (previous != null) {
            cancelWaitingRetry(previous);
        }
        traceEventLogger.assignmentRetryScheduled(
                task.getTid(),
                expectedStatus,
                "NO_ASSIGNMENT_RESULT",
                "TaskAssignWorker",
                "task remained eligible after assignment attempt",
                resolvedRetryDelayMillis
        );
        emitQueueSnapshot(task, expectedStatus, laneState, "RETRY_SCHEDULED", resolvedRetryDelayMillis,
                "task remained eligible after assignment attempt", "SCHEDULED");
        ScheduledFuture<?> future = laneState.retryExecutor.schedule(
                () -> runWaitingRetry(waitingRetry, false, "delayed retry reached due time"),
                resolvedRetryDelayMillis,
                TimeUnit.MILLISECONDS
        );
        waitingRetry.future = future;
    }

    private void cancelWaitingRetry(WaitingRetry waitingRetry) {
        if (waitingRetry == null || !waitingRetry.consume()) {
            return;
        }
        waitingRetry.cancel();
        waitingRetry.laneState.scheduledRetryCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    private boolean runWaitingRetry(WaitingRetry waitingRetry, boolean wakeup, String reason) {
        if (waitingRetry == null || !waitingRetry.consume()) {
            return false;
        }
        waitingRetriesByTaskId.remove(waitingRetry.taskId(), waitingRetry);
        waitingRetry.laneState.scheduledRetryCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
        if (wakeup) {
            waitingRetry.cancel();
        }
        Task task = waitingRetry.task;
        TaskStatus expectedStatus = waitingRetry.expectedStatus;
        LaneState laneState = waitingRetry.laneState;
        long resolvedRetryDelayMillis = waitingRetry.retryDelayMillis;
        if (running && task.getStatus() == expectedStatus) {
            if (enqueueSignal(task, AssignmentSignalReason.RETRY, laneState)) {
                emitQueueSnapshot(task, expectedStatus, laneState,
                        wakeup ? "WAKE_RETRY_ENQUEUED" : "RETRY_ENQUEUED",
                        resolvedRetryDelayMillis,
                        wakeup
                                ? "worker availability wakeup enqueued waiting retry: " + normalizeReason(reason)
                                : "delayed retry enqueued task back into assignment signal queue",
                        "SUCCESS");
                return true;
            }
            emitQueueSnapshot(task, expectedStatus, laneState,
                    wakeup ? "WAKE_RETRY_QUEUE_FULL" : "RETRY_QUEUE_FULL",
                    resolvedRetryDelayMillis,
                    "assignment signal queue is full; retry will be rescheduled", "DEFERRED");
            scheduleRetry(task, expectedStatus, laneState);
            return false;
        }
        releaseTrackedTask(task.getTid());
        emitQueueSnapshot(task, task.getStatus(), laneState,
                wakeup ? "WAKE_RETRY_DROPPED" : "RETRY_DROPPED",
                resolvedRetryDelayMillis,
                wakeup
                        ? "worker availability wakeup dropped retry because task is no longer eligible"
                        : "delayed retry was dropped because task is no longer eligible",
                "SKIPPED");
        return false;
    }

    private void notifyAssignmentProcessed(Task task, LaneState laneState) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "PROCESSED", null,
                "assignment attempt finished processing", "SUCCESS");
        listeners.forEach(l -> l.onTaskAssignmentProcessed(task));

        if (previous > 0 && remaining == 0) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "DRAINED", null,
                    "tracked assignment batch drained", "SUCCESS");
            listeners.forEach(TaskAssignmentQueueListener::onAssignmentQueueDrained);
        }
    }

    private void completeUnprocessedTrackedTask(Task task,
                                                LaneState laneState,
                                                String queueAction,
                                                String reason,
                                                String result) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, queueAction, null, reason, result);
        if (previous > 0 && remaining == 0) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState,
                    "DRAINED", null, "tracked assignment batch drained", "SUCCESS");
            listeners.forEach(TaskAssignmentQueueListener::onAssignmentQueueDrained);
        }
    }

    private LaneState resolveLaneState(Task task) {
        return laneStates.get(resolveDispatchLane(task));
    }

    private TaskRuntimeProfile.DispatchLane resolveDispatchLane(Task task) {
        return taskRuntimeProfileResolver.resolve(task).dispatchLane();
    }

    private int totalQueueDepth() {
        return laneStates.values().stream().mapToInt(LaneState::queueDepth).sum();
    }

    private int totalScheduledRetryCount() {
        return laneStates.values().stream().mapToInt(lane -> lane.scheduledRetryCount.get()).sum();
    }

    private SubmitResult submit(Task task, boolean trackedBatchSubmission) {
        LaneState laneState = resolveLaneState(task);
        if (!running || laneState == null) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "UNAVAILABLE", null,
                    "assignment signal worker is not running", "REJECTED");
            return SubmitResult.REJECTED_UNAVAILABLE;
        }
        if (!trackTask(task)) {
            if (markDeferredRequeue(task)) {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "REQUEUE_MARKED", null,
                        "task requested another dispatch while an assignment cycle is still in progress", "DEFERRED");
                return SubmitResult.DEFERRED_REQUEUE_MARKED;
            } else {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "DEDUP_SKIPPED", null,
                        "task is already queued, processing, or waiting retry", "SKIPPED");
                return SubmitResult.DEDUP_SKIPPED;
            }
        }
        if (!enqueueSignal(task, AssignmentSignalReason.SUBMITTED, laneState)) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "QUEUE_FULL", null,
                    "assignment signal queue is full; submit will be retried", "DEFERRED");
            scheduleSubmissionRetry(task, laneState);
            return SubmitResult.RETRY_SCHEDULED;
        }
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "SUBMITTED", null,
                "task submitted to assignment signal queue", "SUCCESS");
        return SubmitResult.ACCEPTED;
    }

    private void completeRejectedBatchSubmission(Task task) {
        completeUnprocessedTrackedTask(task, resolveLaneState(task), "SUBMIT_REJECTED",
                "task did not enter the tracked assignment signal flow", "SKIPPED");
    }

    public enum SubmitResult {
        ACCEPTED,
        DEDUP_SKIPPED,
        DEFERRED_REQUEUE_MARKED,
        RETRY_SCHEDULED,
        REJECTED_UNAVAILABLE;

        public boolean acceptsSignal() {
            return this != REJECTED_UNAVAILABLE;
        }

        public boolean isHardRejected() {
            return this == REJECTED_UNAVAILABLE;
        }
    }

    private enum AssignmentSignalReason {
        SUBMITTED,
        RETRY,
        REQUEUE
    }

    private record TaskAssignmentSignal(Task task,
                                        AssignmentSignalReason reason,
                                        int priorityOrdinal,
                                        long sequence) implements Comparable<TaskAssignmentSignal> {

        @Override
        public int compareTo(TaskAssignmentSignal other) {
            int priorityComparison = Integer.compare(priorityOrdinal, other.priorityOrdinal);
            if (priorityComparison != 0) {
                return priorityComparison;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    }

    private static final class WaitingRetry {
        private final Task task;
        private final TaskStatus expectedStatus;
        private final LaneState laneState;
        private final long retryDelayMillis;
        private final AtomicBoolean consumed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        private WaitingRetry(Task task,
                             TaskStatus expectedStatus,
                             LaneState laneState,
                             long retryDelayMillis) {
            this.task = task;
            this.expectedStatus = expectedStatus;
            this.laneState = laneState;
            this.retryDelayMillis = retryDelayMillis;
        }

        private String taskId() {
            return task != null ? task.getTid() : null;
        }

        private boolean consume() {
            return consumed.compareAndSet(false, true);
        }

        private void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

    private static final class LaneState {
        private final TaskRuntimeProfile.DispatchLane lane;
        private final PriorityBlockingQueue<TaskAssignmentSignal> queue = new PriorityBlockingQueue<>();
        private final Semaphore capacity;
        private final AtomicInteger scheduledRetryCount = new AtomicInteger(0);
        private ExecutorService executor;
        private ScheduledExecutorService retryExecutor;

        private LaneState(TaskRuntimeProfile.DispatchLane lane, int queueCapacity) {
            this.lane = lane;
            this.capacity = new Semaphore(Math.max(1, queueCapacity));
        }

        private boolean offer(TaskAssignmentSignal signal) {
            if (signal == null || !capacity.tryAcquire()) {
                return false;
            }
            boolean offered = false;
            try {
                offered = queue.offer(signal);
                return offered;
            } finally {
                if (!offered) {
                    capacity.release();
                }
            }
        }

        private TaskAssignmentSignal take() throws InterruptedException {
            TaskAssignmentSignal signal = queue.take();
            capacity.release();
            return signal;
        }

        private int queueDepth() {
            return queue.size();
        }
    }
}
