package com.xa.mass.server.task.observation;

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
public final class TaskLeaseProjectionService implements TaskLeaseProjectionMetrics, SmartLifecycle, DisposableBean {
    private record Notice(String taskId, List<String> ids, long queuedAt) { }
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
            String task = projects.requireManagedTaskId(definition.projectId(), definition.workerGroupId());
            bound.computeIfAbsent(task, ignored -> new ArrayList<>()).add(definition);
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
        synchronized (this) {
            if (!running || messageIds.size() > 1000 || !queue.offer(new Notice(taskId, List.copyOf(messageIds), System.nanoTime())))
                dropped.add(messageIds.size());
            else accepted.add(messageIds.size());
        }
    }
    private void consume() {
        try {
            while (running) {
                var notices = new ArrayList<Notice>();
                notices.add(queue.take()); queue.drainTo(notices, 15);
                var idsByTask = new LinkedHashMap<String, Set<String>>();
                for (var notice : notices) {
                    idsByTask.computeIfAbsent(notice.taskId(), ignored -> new LinkedHashSet<>()).addAll(notice.ids());
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
                var snapshots = results.loadTaskItemResults(task, ids.subList(offset, Math.min(ids.size(), offset + 1000)));
                for (var route : routes.get(task)) {
                    if (!running) return;
                    try {
                        var deadlines = route.project().apply(snapshots);
                        matching.platformLeases().record(route.workerGroupId(), route.poolName(), deadlines);
                        recorded.add(deadlines.size());
                    } catch (RuntimeException failure) { failures.increment(); }
                }
            } catch (RuntimeException failure) { failures.increment(); }
        }
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
            while ((notice = queue.poll()) != null) dropped.add(notice.ids().size());
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
