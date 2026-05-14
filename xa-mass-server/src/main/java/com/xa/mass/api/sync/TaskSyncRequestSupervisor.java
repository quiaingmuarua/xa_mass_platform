package com.xa.mass.api.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-owned guardrail and metrics owner for synchronous task item waits.
 */
@Component
public class TaskSyncRequestSupervisor {

    private static final int DEFAULT_GLOBAL_LIMIT =
            Integer.getInteger("xa.mass.api.taskSyncGlobalInFlightLimit", 500);
    private static final int DEFAULT_PROJECT_LIMIT =
            Integer.getInteger("xa.mass.api.taskSyncProjectInFlightLimit", 100);
    private static final int DEFAULT_TASK_LIMIT =
            Integer.getInteger("xa.mass.api.taskSyncPerTaskInFlightLimit", 20);

    private final int globalLimit;
    private final int projectLimit;
    private final int taskLimit;
    private final AtomicInteger globalInFlight = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> projectInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> taskInFlight = new ConcurrentHashMap<>();
    private final Counter acceptedCounter;
    private final Counter syncedCounter;
    private final Counter timedOutCounter;
    private final Counter failedCounter;
    private final Counter cancelledCounter;
    private final Counter rejectedGlobalCounter;
    private final Counter rejectedProjectCounter;
    private final Counter rejectedTaskCounter;
    private final Timer waitTimer;
    private final Timer syncedWaitTimer;
    private final Timer timeoutWaitTimer;

    public TaskSyncRequestSupervisor() {
        this(null, DEFAULT_GLOBAL_LIMIT, DEFAULT_PROJECT_LIMIT, DEFAULT_TASK_LIMIT);
    }

    @Autowired
    public TaskSyncRequestSupervisor(@Nullable MeterRegistry meterRegistry) {
        this(meterRegistry, DEFAULT_GLOBAL_LIMIT, DEFAULT_PROJECT_LIMIT, DEFAULT_TASK_LIMIT);
    }

    public TaskSyncRequestSupervisor(@Nullable MeterRegistry meterRegistry,
                                     int globalLimit,
                                     int projectLimit,
                                     int taskLimit) {
        this.globalLimit = Math.max(1, globalLimit);
        this.projectLimit = Math.max(1, projectLimit);
        this.taskLimit = Math.max(1, taskLimit);
        this.acceptedCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.accepted").register(meterRegistry) : null;
        this.syncedCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.synced").register(meterRegistry) : null;
        this.timedOutCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.timed_out").register(meterRegistry) : null;
        this.failedCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.failed").register(meterRegistry) : null;
        this.cancelledCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.cancelled").register(meterRegistry) : null;
        this.rejectedGlobalCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.rejected.global").register(meterRegistry) : null;
        this.rejectedProjectCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.rejected.project").register(meterRegistry) : null;
        this.rejectedTaskCounter = meterRegistry != null ? Counter.builder("xa.mass.task.sync.requests.rejected.task").register(meterRegistry) : null;
        this.waitTimer = meterRegistry != null ? Timer.builder("xa.mass.task.sync.wait").register(meterRegistry) : null;
        this.syncedWaitTimer = meterRegistry != null ? Timer.builder("xa.mass.task.sync.wait.synced").register(meterRegistry) : null;
        this.timeoutWaitTimer = meterRegistry != null ? Timer.builder("xa.mass.task.sync.wait.timed_out").register(meterRegistry) : null;
        if (meterRegistry != null) {
            Gauge.builder("xa.mass.task.sync.inflight", globalInFlight, AtomicInteger::get)
                    .description("Current in-flight synchronous task item waits")
                    .register(meterRegistry);
            Gauge.builder("xa.mass.task.sync.inflight.projects", projectInFlight, ConcurrentHashMap::size)
                    .description("Projects currently holding synchronous task item waits")
                    .register(meterRegistry);
            Gauge.builder("xa.mass.task.sync.inflight.tasks", taskInFlight, ConcurrentHashMap::size)
                    .description("Tasks currently holding synchronous task item waits")
                    .register(meterRegistry);
        }
    }

    public SyncLease acquire(String project, String taskId) {
        String normalizedProject = normalizeProject(project);
        String normalizedTaskId = normalizeTaskId(taskId);

        if (globalInFlight.incrementAndGet() > globalLimit) {
            globalInFlight.decrementAndGet();
            incrementCounter(rejectedGlobalCounter);
            throw new SyncCapacityExceededException("Too many in-flight sync task requests: global limit reached");
        }

        AtomicInteger projectCounter = projectInFlight.computeIfAbsent(normalizedProject, ignored -> new AtomicInteger());
        if (projectCounter.incrementAndGet() > projectLimit) {
            decrementAndRemove(projectInFlight, normalizedProject, projectCounter);
            globalInFlight.decrementAndGet();
            incrementCounter(rejectedProjectCounter);
            throw new SyncCapacityExceededException("Too many in-flight sync task requests for project: " + normalizedProject);
        }

        AtomicInteger taskCounter = taskInFlight.computeIfAbsent(normalizedTaskId, ignored -> new AtomicInteger());
        if (taskCounter.incrementAndGet() > taskLimit) {
            decrementAndRemove(taskInFlight, normalizedTaskId, taskCounter);
            decrementAndRemove(projectInFlight, normalizedProject, projectCounter);
            globalInFlight.decrementAndGet();
            incrementCounter(rejectedTaskCounter);
            throw new SyncCapacityExceededException("Too many in-flight sync task requests for task: " + normalizedTaskId);
        }

        incrementCounter(acceptedCounter);
        return new SyncLease(normalizedProject, normalizedTaskId, projectCounter, taskCounter, System.nanoTime());
    }

    private void decrementAndRemove(ConcurrentHashMap<String, AtomicInteger> counters,
                                    String key,
                                    AtomicInteger counter) {
        int next = counter.decrementAndGet();
        if (next <= 0) {
            counters.remove(key, counter);
        }
    }

    private String normalizeProject(String project) {
        if (project == null || project.isBlank()) {
            return "_unknown";
        }
        return project.trim();
    }

    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId.trim();
    }

    private void incrementCounter(@Nullable Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private void recordTimer(@Nullable Timer timer, long durationNanos) {
        if (timer != null) {
            timer.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public enum CompletionOutcome {
        SYNCED,
        TIMED_OUT,
        FAILED,
        CANCELLED
    }

    public final class SyncLease {

        private final String project;
        private final String taskId;
        private final AtomicInteger projectCounter;
        private final AtomicInteger taskCounter;
        private final long startNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private SyncLease(String project,
                          String taskId,
                          AtomicInteger projectCounter,
                          AtomicInteger taskCounter,
                          long startNanos) {
            this.project = project;
            this.taskId = taskId;
            this.projectCounter = projectCounter;
            this.taskCounter = taskCounter;
            this.startNanos = startNanos;
        }

        public void finish(CompletionOutcome outcome) {
            CompletionOutcome resolvedOutcome = Objects.requireNonNull(outcome, "outcome");
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            long durationNanos = Math.max(0L, System.nanoTime() - startNanos);
            recordTimer(waitTimer, durationNanos);
            switch (resolvedOutcome) {
                case SYNCED -> {
                    incrementCounter(syncedCounter);
                    recordTimer(syncedWaitTimer, durationNanos);
                }
                case TIMED_OUT -> {
                    incrementCounter(timedOutCounter);
                    recordTimer(timeoutWaitTimer, durationNanos);
                }
                case FAILED -> incrementCounter(failedCounter);
                case CANCELLED -> incrementCounter(cancelledCounter);
            }
            decrementAndRemove(taskInFlight, taskId, taskCounter);
            decrementAndRemove(projectInFlight, project, projectCounter);
            globalInFlight.decrementAndGet();
        }
    }

    public static final class SyncCapacityExceededException extends RuntimeException {
        public SyncCapacityExceededException(String message) {
            super(message);
        }
    }
}
