package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskCreationUnconfirmedException;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Request-driven finite Task composition. Only bounded, current-process submission identity is retained. */
public final class AppCheckTaskService implements SmartLifecycle, AutoCloseable {
    public static final String PROJECT = "app-checks";
    public static final String EVENT = "extension.worker.app.registration.check";
    static final Map<String, String> APPS = Map.of("app-a", "app-a-sim", "app-b", "app-b-sim");
    private static final int MAX_REQUESTS = 50, MAX_NUMBERS = 50_000, MAX_ACTIVE = 2;
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService queries;
    private final TaskCreationService creation;
    private final TaskDataService data;
    private final TaskLifecycleService lifecycle;
    private final Clock clock;
    private final Object gate = new Object();
    private final Map<String, Submission> requests = new HashMap<>();
    private volatile boolean running;
    private boolean closed;
    private int active, admittedNumbers;

    public AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                               TaskDataService data, TaskLifecycleService lifecycle) {
        this(projects, queries, creation, data, lifecycle, Clock.systemUTC());
    }

    AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                        TaskDataService data, TaskLifecycleService lifecycle, Clock clock) {
        this.projects = projects; this.queries = queries; this.creation = creation;
        this.data = data; this.lifecycle = lifecycle; this.clock = clock;
    }

    @Override public void start() {
        for (String group : APPS.values()) projects.requireManagedTaskId(PROJECT, group);
        synchronized (gate) {
            if (closed) throw new IllegalStateException("App checks is closed");
            running = true;
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }
    private void requireRunning() {
        if (!running) throw new RequestFailure(503, "App checks unavailable", null);
    }

    public Map<String, Object> catalog() {
        requireRunning();
        return Map.of("projectId", PROJECT, "name", "应用注册查询", "version", "0.1.0-preview",
                "apps", List.of("app-a", "app-b").stream().map(app -> Map.of("appId", app, "workerGroupId", APPS.get(app))).toList(),
                "countries", List.of("CN", "US", "GB"),
                "limits", Map.of("tasks", MAX_REQUESTS, "items", MAX_NUMBERS, "numbersPerTask", AppCheckSpecification.MAX_NUMBERS),
                "simulationExample", Map.of("ranges", Map.of("registered", List.of(0, 500),
                        "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), "delayMs", List.of(2000, 5000)));
    }

    public TaskCreateResponse create(Map<String, Object> input) {
        final AppCheckSpecification specification;
        try { specification = AppCheckSpecification.parse(input); }
        catch (IllegalArgumentException invalid) { throw new RequestFailure(400, invalid.getMessage(), null); }
        Submission submission;
        boolean owner;
        synchronized (gate) {
            requireRunning();
            submission = requests.get(specification.requestId());
            owner = submission == null;
            if (!owner && !submission.specification().equals(specification))
                throw new RequestFailure(409, "requestId has different content", null);
            if (owner) {
                if (active >= MAX_ACTIVE || requests.size() >= MAX_REQUESTS || admittedNumbers + specification.numbers().size() > MAX_NUMBERS)
                    throw new RequestFailure(429, "App checks submission capacity exhausted", null);
                LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                submission = new Submission(specification, date, specification.salt(date), new CompletableFuture<>());
                requests.put(specification.requestId(), submission);
                active++;
                admittedNumbers += specification.numbers().size();
            }
        }
        if (owner) {
            try { submission.result().complete(submit(submission)); }
            catch (RuntimeException error) { submission.result().completeExceptionally(error); }
            finally { synchronized (gate) { active--; gate.notifyAll(); } }
        }
        try { return submission.result().join(); }
        catch (CompletionException failure) { throw (RuntimeException) failure.getCause(); }
    }

    private TaskCreateResponse submit(Submission submission) {
        var specification = submission.specification();
        String taskId = null;
        try {
            var metadata = Map.of("scenario", PROJECT, "appId", specification.appId(), "country", specification.country(),
                    "simulation", Jsons.toJson(specification.simulation()), "salt", submission.salt(), "saltDate", submission.date().toString());
            requireRunning();
            taskId = creation.create(new TaskCreateRequest(PROJECT, APPS.get(specification.appId()), 50, 3,
                    List.of(RefillTarget.of("any", new EligibilityQuery(Map.of()), 100)), specification.name(), metadata)).taskId();
            for (int start = 0; start < specification.numbers().size(); start += 100) {
                requireRunning();
                var items = new ArrayList<TaskItemRequest>();
                for (String number : specification.numbers().subList(start, Math.min(start + 100, specification.numbers().size()))) {
                    var payload = new LinkedHashMap<String, Object>(specification.simulation());
                    payload.put("number", number); payload.put("salt", submission.salt());
                    items.add(new TaskItemRequest(UUID.randomUUID().toString(), EVENT, Map.copyOf(payload), 5, 600_000L,
                            new WorkerQuery("worker.assignment.available", Map.of())));
                }
                var appended = data.appendFiniteTaskItems(taskId, items);
                if (appended.size() != items.size() || items.stream().anyMatch(item -> appended.get(item.messageId()) == null
                        || !"applied".equals(appended.get(item.messageId()).status().wireValue())))
                    throw new IllegalStateException("Item append is unconfirmed");
            }
            requireRunning();
            lifecycle.approve(taskId);
            return new TaskCreateResponse(taskId);
        } catch (RuntimeException error) {
            if (error instanceof TaskCreationUnconfirmedException unknown) taskId = unknown.taskId();
            var failure = new RequestFailure(503, "Submission is unconfirmed; do not recreate or retry automatically", taskId);
            failure.initCause(error);
            throw failure;
        }
    }

    public Map<String, Object> list(int limit) {
        requireRunning();
        if (limit < 1 || limit > 100) throw new RequestFailure(400, "limit must be in 1..100", null);
        var page = queries.list(PROJECT, limit);
        var ids = page.tasks().stream().filter(AppCheckTaskService::isCheckTask).map(ProjectTaskQueryService.Entry::taskId).toList();
        var counts = data.observeItemScoreCounts(ids);
        var rows = page.tasks().stream().map(entry -> {
            var row = taskView(entry);
            if (isCheckTask(entry)) addCounts(row, counts.get(entry.taskId()).total(), counts.get(entry.taskId()).countsByTag());
            return row;
        }).toList();
        return Map.of("tasks", rows, "truncated", page.truncated());
    }

    public Map<String, Object> get(String taskId) {
        requireRunning();
        var entry = queries.get(PROJECT, taskId);
        var row = taskView(entry);
        if (isCheckTask(entry)) {
            var counts = data.observeItemScoreCounts(List.of(taskId)).get(taskId);
            addCounts(row, counts.total(), counts.countsByTag());
        }
        var preview = data.previewTaskResults(taskId);
        String group = entry.task() == null ? null : entry.task().workerGroupId();
        return Map.of("task", row, "results", preview.results().stream().map(result -> resultView(group, result)).toList(),
                "resultsTruncated", preview.truncated());
    }

    private static boolean isCheckTask(ProjectTaskQueryService.Entry entry) {
        return entry.task() != null && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && PROJECT.equals(entry.task().metadata().get("scenario"));
    }

    private static Map<String, Object> taskView(ProjectTaskQueryService.Entry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("taskId", entry.taskId()); row.put("createdAtMillis", entry.createdAtMillis()); row.put("state", entry.scoreBand());
        row.put("workerGroupId", entry.task() == null ? null : entry.task().workerGroupId());
        row.put("managed", entry.task() != null && "PARK_WHEN_IDLE".equals(entry.task().idleDisposition()));
        if (entry.task() != null && entry.task().name() != null) row.put("name", entry.task().name());
        if (isCheckTask(entry)) {
            var metadata = entry.task().metadata();
            for (String field : List.of("appId", "country", "salt", "saltDate"))
                if (metadata.containsKey(field)) row.put(field, metadata.get(field));
            if (metadata.containsKey("simulation")) {
                try { row.put("simulation", Jsons.parseObject(metadata.get("simulation"))); }
                catch (IllegalArgumentException invalid) { row.put("configurationError", "Simulation cannot be parsed"); }
            }
        }
        return row;
    }

    private static void addCounts(Map<String, Object> row, long total, Map<Integer, Long> tags) {
        row.put("totalCount", total); row.put("activeCount", tags.get(1)); row.put("failedCount", tags.get(5));
        long succeeded = 0;
        for (int tag = 6; tag <= 9; tag++) succeeded += tags.get(tag);
        row.put("succeededCount", succeeded);
    }

    private static Map<String, Object> resultView(String group, TaskDataService.ResultEntry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("messageId", entry.messageId()); row.put("resultStatus", entry.result().status().wireValue());
        var item = entry.item();
        if (item != null && item.payload().get("number") instanceof String number) row.put("number", number);
        if (entry.result().status() == TaskItemResultStatus.FAILED) return row;
        try {
            var content = Jsons.parseObject(entry.result().opaqueResultPayload());
            if (item == null || !EVENT.equals(item.eventCode()) || !Objects.equals(content.get("number"), item.payload().get("number"))
                    || group == null || !group.equals(content.get("workerGroupId")) || !(content.get("registered") instanceof Boolean))
                throw new IllegalArgumentException("Result association mismatch");
            AppCheckSpecification.text(content.get("workerId"), 256);
            long delay = AppCheckSpecification.integer(content.get("simulatedDelayMillis"));
            if (delay < 0 || delay > 30_000) throw new IllegalArgumentException("Invalid simulated delay");
            for (String field : List.of("registered", "workerId", "workerGroupId", "simulatedDelayMillis")) row.put(field, content.get(field));
        } catch (RuntimeException invalid) { row.put("contentError", "Application check result cannot be parsed or associated"); }
        return row;
    }

    @Override public void stop() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (gate) {
            closed = true; running = false;
            while (active > 0 && System.nanoTime() < deadline) {
                try { TimeUnit.NANOSECONDS.timedWait(gate, Math.max(1, deadline - System.nanoTime())); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
    @Override public void close() { stop(); }

    private record Submission(AppCheckSpecification specification, LocalDate date, String salt,
                              CompletableFuture<TaskCreateResponse> result) {}

    static final class RequestFailure extends RuntimeException {
        final int status;
        final String taskId;
        RequestFailure(int status, String message, String taskId) { super(message); this.status = status; this.taskId = taskId; }
    }
}
