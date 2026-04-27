package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.TaskRuntimeAssignmentRetryOptionsResolver;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lane-aware assignment signal worker.
 *
 * <p>This component keeps the task-level matching plus message-level claim
 * model, while separating assignment signals into workload lanes so later
 * throughput work is not forced back into a single global queue.
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
    private final TaskRuntimeAssignmentRetryOptionsResolver taskRuntimeAssignmentRetryOptionsResolver;
    private final Map<TaskRuntimeProfile.DispatchLane, LaneState> laneStates =
            new EnumMap<>(TaskRuntimeProfile.DispatchLane.class);
    private final List<TaskAssignmentQueueListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Set<String> trackedTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deferredRequeueTaskIds = ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener) {
        this(workerAssignListener, DEFAULT_RETRY_DELAY_MILLIS);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener, long retryDelayMillis) {
        this(workerAssignListener, retryDelayMillis, DEFAULT_ASSIGNMENT_QUEUE_CAPACITY);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                            long retryDelayMillis,
                            int assignmentQueueCapacity) {
        this(workerAssignListener,
                retryDelayMillis,
                assignmentQueueCapacity,
                new TaskRuntimeAssignmentRetryOptionsResolver());
    }

    TaskAssignWorker(TaskWorkerAssignListener workerAssignListener,
                     long retryDelayMillis,
                     int assignmentQueueCapacity,
                     TaskRuntimeAssignmentRetryOptionsResolver taskRuntimeAssignmentRetryOptionsResolver) {
        this.workerAssignListener = workerAssignListener;
        this.retryDelayMillis = retryDelayMillis;
        this.assignmentQueueCapacity = Math.max(1, assignmentQueueCapacity);
        this.taskRuntimeAssignmentRetryOptionsResolver = taskRuntimeAssignmentRetryOptionsResolver;
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
            if (!submit(task, true)) {
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
        return submit(task, false);
    }

    public void stop() {
        running = false;
        trackedTaskIds.clear();
        deferredRequeueTaskIds.clear();
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
                TaskAssignmentSignal signal = laneState.queue.take();
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
        TraceEventLogger.assignmentQueueSnapshot(
                task,
                taskStatus,
                laneState != null ? laneState.lane.name() : resolveDispatchLane(task).name(),
                laneState != null ? laneState.queue.size() : totalQueueDepth(),
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
        return laneState.queue.offer(new TaskAssignmentSignal(task, reason));
    }

    private void scheduleRetry(Task task, TaskStatus expectedStatus, LaneState laneState) {
        if (laneState == null || laneState.retryExecutor == null) {
            return;
        }
        long resolvedRetryDelayMillis = taskRuntimeAssignmentRetryOptionsResolver.resolve(task, retryDelayMillis);
        laneState.scheduledRetryCount.incrementAndGet();
        TraceEventLogger.assignmentRetryScheduled(
                task.getTid(),
                expectedStatus,
                "NO_ASSIGNMENT_RESULT",
                "TaskAssignWorker",
                "task remained eligible after assignment attempt",
                resolvedRetryDelayMillis
        );
        emitQueueSnapshot(task, expectedStatus, laneState, "RETRY_SCHEDULED", resolvedRetryDelayMillis,
                "task remained eligible after assignment attempt", "SCHEDULED");
        laneState.retryExecutor.schedule(() -> {
            laneState.scheduledRetryCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            if (running && task.getStatus() == expectedStatus) {
                if (enqueueSignal(task, AssignmentSignalReason.RETRY, laneState)) {
                    emitQueueSnapshot(task, expectedStatus, laneState, "RETRY_ENQUEUED", resolvedRetryDelayMillis,
                            "delayed retry enqueued task back into assignment signal queue", "SUCCESS");
                } else {
                    emitQueueSnapshot(task, expectedStatus, laneState, "RETRY_QUEUE_FULL", resolvedRetryDelayMillis,
                            "assignment signal queue is full; retry will be rescheduled", "DEFERRED");
                    scheduleRetry(task, expectedStatus, laneState);
                }
                return;
            }
            releaseTrackedTask(task.getTid());
            emitQueueSnapshot(task, task.getStatus(), laneState, "RETRY_DROPPED", resolvedRetryDelayMillis,
                    "delayed retry was dropped because task is no longer eligible", "SKIPPED");
        }, resolvedRetryDelayMillis, TimeUnit.MILLISECONDS);
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

    private LaneState resolveLaneState(Task task) {
        return laneStates.get(resolveDispatchLane(task));
    }

    private TaskRuntimeProfile.DispatchLane resolveDispatchLane(Task task) {
        return TASK_RUNTIME_PROFILE_RESOLVER.resolve(task).dispatchLane();
    }

    private int totalQueueDepth() {
        return laneStates.values().stream().mapToInt(lane -> lane.queue.size()).sum();
    }

    private int totalScheduledRetryCount() {
        return laneStates.values().stream().mapToInt(lane -> lane.scheduledRetryCount.get()).sum();
    }

    private boolean submit(Task task, boolean trackedBatchSubmission) {
        LaneState laneState = resolveLaneState(task);
        if (!trackTask(task)) {
            if (markDeferredRequeue(task)) {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "REQUEUE_MARKED", null,
                        "task requested another dispatch while an assignment cycle is still in progress", "DEFERRED");
            } else {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "DEDUP_SKIPPED", null,
                        "task is already queued, processing, or waiting retry", "SKIPPED");
            }
            return false;
        }
        if (!enqueueSignal(task, AssignmentSignalReason.SUBMITTED, laneState)) {
            releaseTrackedTask(task != null ? task.getTid() : null);
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "QUEUE_FULL", null,
                    "assignment signal queue is full", "REJECTED");
            return false;
        }
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, laneState, "SUBMITTED", null,
                "task submitted to assignment signal queue", "SUCCESS");
        return true;
    }

    private void completeRejectedBatchSubmission(Task task) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, resolveLaneState(task),
                "BATCH_SUBMIT_REJECTED", null,
                "task did not enter the tracked assignment batch", "SKIPPED");
        if (previous > 0 && remaining == 0) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, resolveLaneState(task),
                    "DRAINED", null, "tracked assignment batch drained", "SUCCESS");
            listeners.forEach(TaskAssignmentQueueListener::onAssignmentQueueDrained);
        }
    }

    private enum AssignmentSignalReason {
        SUBMITTED,
        RETRY,
        REQUEUE
    }

    private record TaskAssignmentSignal(Task task, AssignmentSignalReason reason) {
    }

    private static final class LaneState {
        private final TaskRuntimeProfile.DispatchLane lane;
        private final BlockingQueue<TaskAssignmentSignal> queue;
        private final AtomicInteger scheduledRetryCount = new AtomicInteger(0);
        private ExecutorService executor;
        private ScheduledExecutorService retryExecutor;

        private LaneState(TaskRuntimeProfile.DispatchLane lane, int queueCapacity) {
            this.lane = lane;
            this.queue = new LinkedBlockingQueue<>(queueCapacity);
        }
    }
}
