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
import java.util.concurrent.TimeUnit;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import org.springframework.context.SmartLifecycle;

/** Request-driven creation, file import and result projection over existing Task application services. */
public final class AppCheckTaskService implements SmartLifecycle, AutoCloseable {
    public static final String PROJECT = "app-checks";
    public static final String EVENT = "extension.worker.app.registration.check";
    static final Map<String, String> APPS = Map.of("app-a", "app-a-sim", "app-b", "app-b-sim");
    private static final int MAX_ACTIVE_IMPORTS = 2;
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService queries;
    private final TaskCreationService creation;
    private final TaskDataService data;
    private final TaskLifecycleService lifecycle;
    private final Clock clock;
    private final Object gate = new Object();
    private final OperationGuard operations;
    private final TaskResultsExportService exports;
    private volatile boolean running;
    private boolean closed;
    private int active;

    public AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                               TaskDataService data, TaskLifecycleService lifecycle, OperationGuard operations, TaskResultsExportService exports) {
        this(projects, queries, creation, data, lifecycle, operations, exports, Clock.systemUTC());
    }

    AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                        TaskDataService data, TaskLifecycleService lifecycle, OperationGuard operations, TaskResultsExportService exports, Clock clock) {
        this.projects = projects; this.queries = queries; this.creation = creation;
        this.data = data; this.lifecycle = lifecycle; this.clock = clock; this.operations = operations; this.exports = exports;
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
        return Map.of("projectId", PROJECT, "name", "应用注册查询", "version", "0.2.0-preview",
                "apps", List.of("app-a", "app-b").stream().map(app -> Map.of("appId", app, "workerGroupId", APPS.get(app))).toList(),
                "countries", List.of("CN", "US", "GB"),
                "limits", Map.of("numbersPerImport", AppCheckNumberFile.MAX_NUMBERS, "importFileBytes", AppCheckNumberFile.MAX_BYTES),
                "simulationExample", Map.of("ranges", Map.of("registered", List.of(0, 500),
                        "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), "delayMs", List.of(2000, 5000)));
    }

    public TaskCreateResponse create(Map<String, Object> input) {
        requireRunning();
        final AppCheckSpecification specification;
        try { specification = AppCheckSpecification.parse(input); }
        catch (IllegalArgumentException invalid) { throw new RequestFailure(400, invalid.getMessage(), null); }
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var metadata = Map.of("scenario", PROJECT, "inputVersion", "2", "appId", specification.appId(), "country", specification.country(),
                "simulation", Jsons.toJson(specification.simulation()), "salt", specification.salt(date), "saltDate", date.toString());
        String name = specification.appId() + " · " + specification.country() + " · " + clock.instant();
        try {
            return creation.createForRequest(new TaskCreateRequest(PROJECT, APPS.get(specification.appId()), 50, 3,
                    List.of(RefillTarget.of("assignment-window", new EligibilityQuery(Map.of()), 100)), name, metadata),
                    specification.requestId(), specification.fingerprint());
        } catch (TaskCreationUnconfirmedException unknown) {
            throw new RequestFailure(503, "创建结果未确认，请核对已知任务；不会自动重建", unknown.taskId());
        }
    }

    private ProjectTaskQueryService.Entry requireCheck(String taskId) {
        requireRunning();
        var entry = queries.get(PROJECT, taskId);
        if (!isCheckTask(entry)) throw new RequestFailure(400, "只支持 App Checks 有限任务", taskId);
        return entry;
    }

    private static boolean hasWindowSupply(ProjectTaskQueryService.Entry entry) {
        return entry.task().refill().stream().anyMatch(target -> target.poolName().equals("assignment-window")
                && target.target().query().isEmpty());
    }

    private static final String OLD_SUPPLY = "旧供给配置任务不支持继续导入或启动，请关闭后创建新任务";

    private static void requireWindowSupply(ProjectTaskQueryService.Entry entry) {
        if (!hasWindowSupply(entry)) throw new RequestFailure(409, OLD_SUPPLY, entry.taskId());
    }

    public ImportReceipt importNumbers(String taskId, InputStream input) {
        synchronized (gate) {
            requireRunning();
            if (active >= MAX_ACTIVE_IMPORTS) throw new RequestFailure(429, "导入繁忙，请稍后重新发起", taskId);
            active++;
        }
        try {
            return operations.taskMutation(taskId, () -> {
                var entry = requireCheck(taskId);
                requireWindowSupply(entry);
                if (!"pre_review".equals(entry.scoreBand()) || !"2".equals(entry.task().metadata().get("inputVersion")))
                    throw new RequestFailure(409, "只有新版本待审核任务支持导入", taskId);
                long added = 0, existing = 0;
                try (var file = AppCheckNumberFile.read(input, entry.task().metadata().get("country"));
                     var reader = Files.newBufferedReader(file.file(), StandardCharsets.UTF_8)) {
                    var simulation = AppCheckSpecification.simulation(Jsons.parseObject(entry.task().metadata().get("simulation")));
                    var batch = new ArrayList<TaskItemRequest>(100);
                    boolean finished = false;
                    while (!finished) {
                        requireRunning();
                        batch.clear();
                        while (batch.size() < 100) {
                            String number = reader.readLine();
                            if (number == null) { finished = true; break; }
                            var payload = new LinkedHashMap<String, Object>(simulation);
                            payload.put("number", number); payload.put("salt", entry.task().metadata().get("salt"));
                            batch.add(new TaskItemRequest("number-" + AppCheckSpecification.digest("app-checks/v2/number", number),
                                    EVENT, payload, 5, null, new WorkerQuery("worker.assignment.available", Map.of())));
                        }
                        if (batch.isEmpty()) break;
                        var effects = data.importFiniteTaskItems(taskId, List.copyOf(batch));
                        boolean confirmed = true;
                        for (var item : batch) {
                            var effect = effects.get(item.messageId());
                            if (effect != null && effect.status() == ActionOutcome.Status.APPLIED) added++;
                            else if (effect != null && effect.status() == ActionOutcome.Status.UNCHANGED) existing++;
                            else confirmed = false;
                        }
                        if (!confirmed) throw new IllegalStateException("Item import is unconfirmed");
                    }
                    return new ImportReceipt(taskId, file.inputCount(), file.emptyCount(), file.duplicateCount(), file.uniqueCount(), added, existing);
                } catch (RequestFailure invalid) {
                    var failure = new RequestFailure(invalid.status, invalid.getMessage(), taskId);
                    failure.confirmedAdded = added; failure.existing = existing; throw failure;
                } catch (IOException | RuntimeException unknown) {
                    var failure = new RequestFailure(503, "导入结果未确认，已确认批次保留；请核对实际数量或显式重新导入", taskId);
                    failure.confirmedAdded = added; failure.existing = existing; failure.initCause(unknown); throw failure;
                }
            });
        } finally { synchronized (gate) { active--; gate.notifyAll(); } }
    }

    public ActionOutcome approve(String taskId, long expectedCount) {
        return operations.taskMutation(taskId, () -> {
            var entry = requireCheck(taskId);
            requireWindowSupply(entry);
            if (!"pre_review".equals(entry.scoreBand())) throw new RequestFailure(409, "任务不在待审核状态，请刷新核对", taskId);
            long actual = data.observeItemScoreCounts(List.of(taskId)).get(taskId).total();
            if (expectedCount <= 0 || actual != expectedCount)
                throw new RequestFailure(409, "号码数量已变化或为空，请重新核对后启动", taskId);
            return lifecycle.approve(taskId);
        });
    }

    public ActionOutcome closeTask(String taskId) {
        return operations.taskMutation(taskId, () -> { requireCheck(taskId); return lifecycle.close(taskId); });
    }

    public CsvExport export(String taskId, String filter) {
        if (!Set.of("all", "registered", "unregistered").contains(filter)) throw new RequestFailure(400, "无效导出范围", taskId);
        var task = requireCheck(taskId).task();
        return operations.execute("app-checks-csv-export", taskId, () -> {
            Path source = exports.export(taskId).file();
            Path csv = null;
            boolean complete = false;
            try {
                csv = Files.createTempFile("app-check-results-", ".csv");
                long count = 0;
                try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
                     var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                    writer.write("\uFEFF号码,注册状态,应用,地区\r\n");
                    var payloads = new LinkedHashMap<String, String>();
                    boolean finished = false;
                    while (!finished) {
                        requireRunning();
                        payloads.clear();
                        while (payloads.size() < 100) {
                            String line = reader.readLine();
                            if (line == null) { finished = true; break; }
                            var record = Jsons.parseObject(line);
                            payloads.put((String) record.get("messageId"), (String) record.get("opaqueResultPayload"));
                        }
                        if (payloads.isEmpty()) break;
                        var items = data.loadTaskItems(taskId, List.copyOf(payloads.keySet()));
                        for (var result : payloads.entrySet()) {
                            var view = resultView(task.workerGroupId(), new TaskDataService.ResultEntry(result.getKey(), items.get(result.getKey()),
                                    TaskItemResultResponse.succeeded(result.getValue())));
                            if (view.containsKey("contentError") || !(view.get("registered") instanceof Boolean registered)) continue;
                            if (filter.equals("registered") && !registered || filter.equals("unregistered") && registered) continue;
                            writer.write(String.join(",", csvCell((String) view.get("number")), csvCell(registered ? "已注册" : "未注册"),
                                    csvCell(task.metadata().get("appId")), csvCell(task.metadata().get("country"))) + "\r\n");
                            count++;
                        }
                    }
                }
                complete = true;
                return new CsvExport(csv, taskId + "-" + filter + ".csv", count);
            } catch (IOException | RuntimeException error) {
                var failure = new RequestFailure(503, "结果导出失败，请重新发起", taskId); failure.initCause(error); throw failure;
            } finally {
                deleteTemporary(source);
                if (!complete) deleteTemporary(csv);
            }
        });
    }

    private static String csvCell(String value) {
        if (value.matches("^[=+\\-@].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private static void deleteTemporary(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
    public void transferExport(CsvExport file, OutputStream output) throws IOException { exports.transferAndDelete(file.file(), output); }
    public record CsvExport(Path file, String fileName, long count) { }
    public record ImportReceipt(String taskId, long inputCount, long emptyCount, long duplicateCount, int uniqueCount, long addedCount, long existingCount) { }

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
        var entry = requireCheck(taskId);
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
            if (!hasWindowSupply(entry)) row.put("inputUnavailableReason", OLD_SUPPLY);
            var metadata = entry.task().metadata();
            for (String field : List.of("appId", "country", "salt", "saltDate", "inputVersion"))
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
            if (item == null || !entry.messageId().equals(item.messageId()) || !EVENT.equals(item.eventCode())
                    || !(item.payload().get("number") instanceof String number) || number.isBlank()
                    || !Objects.equals(content.get("number"), number)
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

    static final class RequestFailure extends RuntimeException {
        final int status;
        final String taskId;
        Long confirmedAdded, existing;
        RequestFailure(int status, String message, String taskId) { super(message); this.status = status; this.taskId = taskId; }
    }
}
