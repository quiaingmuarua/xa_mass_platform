package com.xa.mass.server.task.observation;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.workermatching.MatchingComposition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/** Bounded, lossy stored-Result projection. The Pacer callback never reads or writes Redis. */
@Service
public final class TaskLeaseProjectionService implements TaskLeaseProjectionAccess, SmartLifecycle, DisposableBean {
    private record LeaseWrite(String group, String pool, Map<Coordinate, Long> deadlines) { }
    private record Notice(String taskId, List<String> ids, List<LeaseWrite> writes, int count, long queuedAt) { }
    private final List<TaskLeaseProjection> definitions;
    private final ProjectDirectory projects;
    private final TaskDataService results;
    private final MatchingComposition matching;
    private final ArrayBlockingQueue<Notice> queue = new ArrayBlockingQueue<>(256);
    private final LongAdder accepted = new LongAdder(), dropped = new LongAdder(), failures = new LongAdder(), recorded = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder(), processed = new LongAdder();
    private volatile Map<String, List<TaskLeaseProjection>> routes = Map.of();
    private volatile boolean running;
    private Thread thread;
    public TaskLeaseProjectionService(List<TaskLeaseProjection> definitions, ProjectDirectory projects,
            TaskDataService results, MatchingComposition matching) {
        this.definitions = List.copyOf(definitions); this.projects = projects; this.results = results; this.matching = matching;
    }
    @Override public synchronized void start() {
        if (running || definitions.isEmpty()) return;
        if (thread != null && thread.isAlive()) throw new IllegalStateException("operation=taskLeaseProjection.start consumer still stopping");
        var bound = new LinkedHashMap<String, List<TaskLeaseProjection>>();
        for (var definition : definitions) {
            projects.requireWorkerGroup(definition.projectId(), definition.workerGroupId());
            bound.computeIfAbsent(definition.taskId(), ignored -> new ArrayList<>()).add(definition);
        }
        bound.replaceAll((task, items) -> List.copyOf(items));
        routes = Map.copyOf(bound);
        running = true;
        thread = Thread.ofPlatform().name("task-lease-projection").unstarted(this::consume);
        try { thread.start(); }
        catch (RuntimeException | Error failure) { running = false; thread = null; throw failure; }
    }
    public boolean enabled() { return !definitions.isEmpty(); }
    public void accept(String taskId, List<String> messageIds) {
        if (!routes.containsKey(taskId)) return;
        if (messageIds.isEmpty()) return;
        if (messageIds.size() > 1000) { dropped.add(messageIds.size()); return; }
        offer(new Notice(taskId, List.copyOf(messageIds), List.of(), messageIds.size(), System.nanoTime()));
    }

    /** Converts already consumed content before enqueueing; the callback performs no Redis I/O. */
    @Override public void acceptResults(TaskCaller caller, String taskId, Map<String, TaskItemResultResponse> snapshots) {
        if (!caller.isService()) throw new ServerException(ServerErrorCode.TASK_ACCESS_DENIED, "taskLeaseProjection.acceptResults", null, null);
        var selected = routes.get(taskId);
        if (selected == null) throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskLeaseProjection.acceptResults", "Task has no declared projection", null);
        selected.forEach(route -> caller.requireProject(route.projectId()));
        if (snapshots == null || snapshots.size() > 1000 || snapshots.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskLeaseProjection.acceptResults", "Expected at most 1000 Results", null);
        if (snapshots.isEmpty()) return;
        var captured = Map.copyOf(snapshots);
        var writes = new ArrayList<LeaseWrite>();
        for (var route : selected) {
            try {
                var deadlines = Map.copyOf(route.project().apply(captured));
                if (deadlines.size() > 1000) throw new IllegalArgumentException("Projection exceeds its batch bound");
                if (!deadlines.isEmpty()) writes.add(new LeaseWrite(route.workerGroupId(), route.poolName(), deadlines));
            } catch (RuntimeException failure) { failures.increment(); }
        }
        if (!writes.isEmpty()) offer(new Notice(taskId, List.of(), List.copyOf(writes), captured.size(), System.nanoTime()));
    }

    private synchronized void offer(Notice notice) {
        if (!running || !queue.offer(notice)) dropped.add(notice.count());
        else accepted.add(notice.count());
    }
    private void consume() {
        try {
            while (running) {
                var notices = new ArrayList<Notice>();
                notices.add(queue.take()); queue.drainTo(notices, 15);
                var idsByTask = new LinkedHashMap<String, Set<String>>();
                for (var notice : notices) {
                    if (!notice.ids().isEmpty()) idsByTask.computeIfAbsent(notice.taskId(), ignored -> new LinkedHashSet<>()).addAll(notice.ids());
                    notice.writes().forEach(this::record);
                    latencyNanos.add(System.nanoTime() - notice.queuedAt()); processed.increment();
                }
                idsByTask.forEach((task, ids) -> process(task, List.copyOf(ids)));
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        finally { running = false; }
    }
    private void process(String task, List<String> ids) {
        for (int offset = 0; offset < ids.size() && running; offset += 1000) {
            try {
                var definition = routes.get(task).getFirst();
                var caller = TaskCaller.forProjectService(new com.xa.mass.server.project.ProjectDefinition(
                        definition.projectId(), List.of(definition.workerGroupId())));
                var snapshots = results.loadTaskItemResults(caller, task, ids.subList(offset, Math.min(ids.size(), offset + 1000)));
                for (var route : routes.get(task)) {
                    if (!running) return;
                    try {
                        var deadlines = route.project().apply(snapshots);
                        record(new LeaseWrite(route.workerGroupId(), route.poolName(), deadlines));
                    } catch (RuntimeException failure) { failures.increment(); }
                }
            } catch (RuntimeException failure) { failures.increment(); }
        }
    }
    private void record(LeaseWrite write) {
        if (!running) return;
        try {
            matching.platformLeases().record(write.group(), write.pool(), write.deadlines());
            recorded.add(write.deadlines().size());
        } catch (RuntimeException failure) { failures.increment(); }
    }
    @Override public Map<String, Object> metrics() {
        return Map.of("accepted", accepted.sum(), "dropped", dropped.sum(), "failures", failures.sum(),
                "recorded", recorded.sum(), "queueBatches", queue.size(),
                "meanQueueMillis", processed.sum() == 0 ? 0 : latencyNanos.sum() / processed.sum() / 1_000_000,
                "matching", matching.leaseMetrics());
    }
    @Override public void stop() {
        Thread stopping;
        synchronized (this) {
            running = false; stopping = thread;
            Notice notice;
            while ((notice = queue.poll()) != null) dropped.add(notice.count());
            if (stopping != null) stopping.interrupt();
        }
        if (stopping != null) try {
            stopping.join(Duration.ofSeconds(5));
            if (stopping.isAlive()) throw new IllegalStateException("operation=taskLeaseProjection.stop shutdown budget exceeded");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE - 1; }
    @Override public void destroy() { stop(); }
}
