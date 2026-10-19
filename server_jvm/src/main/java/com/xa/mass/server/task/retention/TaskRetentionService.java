package com.xa.mass.server.task.retention;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.directory.TaskCreationRegistry;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.Nullable;

/** Retention policy and files; never participates in execution scheduling. */
@Service
public final class TaskRetentionService implements SmartLifecycle {
    public static final String PERMISSION = "tasks.retention.manage";
    private static final System.Logger LOG = System.getLogger(TaskRetentionService.class.getName());
    private final TaskRuntime runtime;
    private final TaskResourceCatalog catalog;
    private final TaskScoreBandCore scores;
    private final TaskDirectory directory;
    private final TaskCreationRegistry creations;
    private final com.xa.mass.server.task.credit.TaskCreditStartStore creditStarts;
    private final TaskResultArchive archive;
    private final OperationGuard operations;
    private final TaskRetentionProperties properties;
    private final LongSupplier clock;
    private volatile ScheduledExecutorService executor;

    @Autowired
    public TaskRetentionService(TaskRuntime runtime, TaskResourceCatalog catalog, TaskScoreBandCore scores,
            TaskDirectory directory, TaskCreationRegistry creations, TaskResultArchive archive,
            OperationGuard operations, TaskRetentionProperties properties, com.xa.mass.server.task.credit.TaskCreditStartStore creditStarts) {
        this(runtime, catalog, scores, directory, creations, archive, operations, properties, creditStarts, System::currentTimeMillis);
    }
    public TaskRetentionService(TaskRuntime runtime, TaskResourceCatalog catalog, TaskScoreBandCore scores,
            TaskDirectory directory, TaskCreationRegistry creations, TaskResultArchive archive,
            OperationGuard operations, TaskRetentionProperties properties, com.xa.mass.server.task.credit.TaskCreditStartStore creditStarts, LongSupplier clock) {
        this.runtime = runtime; this.catalog = catalog; this.scores = scores; this.directory = directory;
        this.creations = creations; this.archive = archive; this.operations = operations; this.properties = properties; this.creditStarts = creditStarts; this.clock = clock;
    }

    public record Info(String phase, @Nullable Long terminatedAtMillis, @Nullable Long frozenAtMillis, @Nullable Long expiresAtMillis) { }
    public record Result(ActionOutcome outcome, @Nullable Info retention) { }

    public @Nullable Info info(TaskDescriptor task, TaskScoreState score, TaskDataState data) {
        if (task.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE) return null;
        if (data == null || score == null) throw unavailable("observe", new IllegalStateException("Task resources are incomplete"));
        Long ended = score.terminatedAtMillis();
        return new Info(data.phase().name(), ended, data.frozenAtMillis(), ended == null ? null : Math.addExact(ended, properties.retention().toMillis()));
    }

    public Result run(TaskCaller caller, String taskId) {
        caller.requirePermission(PERMISSION);
        if (!properties.enabled()) throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskRetention.run", "Retention is disabled on this instance", null);
        return operations.taskMutation(taskId, () -> {
            try {
            var task = catalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            if (task == null) throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "taskRetention.run", null, null);
            caller.requireTask(task);
            if (task.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE)
                throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskRetention.run", "Only finite Tasks support retention", null);
            return maintain(taskId, clock.getAsLong());
            } catch (ServerException known) { throw known;
            } catch (RuntimeException unavailable) { throw unavailable("run", unavailable); }
        });
    }

    public Result maintain(String taskId, long now) {
        return operations.taskMutation(taskId, () -> {
            try {
                var task = catalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
                var score = scores.getScoreStates(List.of(taskId)).get(taskId);
                if (task == null && score == null) return new Result(ActionOutcome.unchanged(), new Info("PURGED", null, null, null));
                if (task == null || score == null) throw new IllegalStateException("Task resources are incomplete");
                if (task.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE) return new Result(ActionOutcome.unchanged(), null);
                var data = runtime.loadTaskDataStates(List.of(taskId)).get(taskId);
                var info = info(task, score, data);
                Long ended = score.terminatedAtMillis();
                if (ended == null) return new Result(ActionOutcome.unchanged(), info);
                if (data.phase() == TaskDataPhase.PURGING || now >= info.expiresAtMillis()) {
                    runtime.beginTaskPurge(taskId, score.score());
                    archive.remove(taskId);
                    runtime.purgeTaskData(taskId, score.score());
                    var attribution = TaskAttribution.read(task.metadata());
                    if (attribution != null) directory.remove(attribution.projectId(), taskId);
                    creditStarts.remove(taskId);
                    String association = task.metadata().get(TaskCreationRegistry.METADATA_KEY);
                    if (association != null) creations.remove(association, taskId);
                    runtime.finishTaskPurge(taskId, score.score());
                    return new Result(ActionOutcome.applied(), new Info("PURGED", ended, data.frozenAtMillis(), info.expiresAtMillis()));
                }
                if (data.phase() == TaskDataPhase.RELEASED || now < Math.addExact(ended, properties.grace().toMillis()))
                    return new Result(ActionOutcome.unchanged(), info);
                runtime.freezeTaskData(taskId, score.score());
                data = runtime.loadTaskDataStates(List.of(taskId)).get(taskId);
                archive.publish(task, data);
                runtime.releaseTaskPayloads(taskId, score.score());
                return new Result(ActionOutcome.applied(), info(task, score, runtime.loadTaskDataStates(List.of(taskId)).get(taskId)));
            } catch (ServerException known) { throw known;
            } catch (Exception failure) { throw unavailable("maintain", failure); }
        });
    }

    public void runOnce() {
        long now = clock.getAsLong();
        sweep(now, Math.max(0, now - properties.retention().toMillis()), false);
        sweep(now, Math.max(0, now - properties.grace().toMillis()), true);
    }
    private void sweep(long now, long cutoff, boolean archivePass) {
        String cursor = "0";
        do {
            var page = scores.scanTerminalTasks(cutoff, cursor, 100);
            for (var task : page.tasks()) {
                if (archivePass && task.terminatedAtMillis() <= now - properties.retention().toMillis()) continue;
                try { maintain(task.taskId(), clock.getAsLong()); }
                catch (RuntimeException failed) { LOG.log(System.Logger.Level.WARNING, "Task retention deferred for {0}: {1}", task.taskId(), failed.getClass().getSimpleName()); }
            }
            cursor = page.nextCursor();
        } while (!cursor.equals("0") && !Thread.currentThread().isInterrupted());
    }
    @Override public synchronized void start() {
        if (!properties.enabled() || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> { var thread = new Thread(runnable, "task-retention"); thread.setDaemon(true); return thread; });
        executor.scheduleWithFixedDelay(() -> {
            try { runOnce(); }
            catch (RuntimeException failed) { LOG.log(System.Logger.Level.WARNING, "Task retention pass deferred: {0}", failed.getClass().getSimpleName()); }
        }, 0, properties.interval().toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public void stop() {
        ScheduledExecutorService running;
        synchronized (this) { running = executor; executor = null; }
        if (running == null) return;
        running.shutdown();
        try { if (!running.awaitTermination(5, TimeUnit.SECONDS)) running.shutdownNow(); }
        catch (InterruptedException interrupted) { running.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return executor != null; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 1; }
    private static ServerException unavailable(String method, Throwable failure) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskRetention." + method, null, failure);
    }
}
