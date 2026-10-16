package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskCreationUnconfirmedException;
import com.xa.mass.server.task.TaskDataService;
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
    private static final int MAX_ACTIVE_IMPORTS = 2;
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService queries;
    private final TaskCreationService creation;
    private final TaskDataService data;

    private final Clock clock;
    private final Object gate = new Object();
    private final OperationGuard operations;
    private final TaskResultsExportService exports;
    private final AppCheckSettings settings;
    private final AppCheckContracts contracts;
    private volatile boolean running;
    private boolean closed;
    private int active;

    public AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                               TaskDataService data, OperationGuard operations, TaskResultsExportService exports,
                               AppCheckSettings settings, AppCheckContracts contracts) {
        this(projects, queries, creation, data, operations, exports, settings, contracts, Clock.systemUTC());
    }

    AppCheckTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
                        TaskDataService data, OperationGuard operations, TaskResultsExportService exports,
                        AppCheckSettings settings, AppCheckContracts contracts, Clock clock) {
        this.projects = projects; this.queries = queries; this.creation = creation;
        this.data = data;  this.clock = clock; this.operations = operations; this.exports = exports;
        this.settings = Objects.requireNonNull(settings);
        this.contracts = Objects.requireNonNull(contracts);
    }

    @Override public void start() {
        for (String group : settings.workerGroupIds()) projects.requireWorkerGroup(PROJECT, group);
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
        return Map.of("projectId", PROJECT, "name", "批量检查", "version", "0.3.0-preview",
                "checks", settings.checks().entrySet().stream().map(entry -> Map.of("checkId", entry.getKey(), "label", entry.getValue().label(), "workerGroupId", entry.getValue().workerGroupId(), "checkType", entry.getValue().checkType(), "execution", entry.getValue().execution().toMap())).toList(),
                "checkTypes", contracts.descriptions(), "countries", List.of("CN", "US", "GB"),
                "limits", Map.of("seedsPerImport", AppCheckSeedFile.MAX_SEEDS, "importFileBytes", AppCheckSeedFile.MAX_BYTES));
    }

    public TaskCreateResponse create(TaskCaller caller, Map<String, Object> input) {
        caller.requireProject(PROJECT);
        requireRunning();
        final AppCheckSpecification specification;
        try {
            specification = AppCheckSpecification.parse(caller, input);
            var selected = settings.requireCheck(specification.checkId());
            contracts.require(selected.checkType()).seedType().requireCountry(specification.country());
        }
        catch (IllegalArgumentException invalid) { throw new RequestFailure(400, invalid.getMessage(), null); }
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var app = settings.requireCheck(specification.checkId());
        var parameters = contracts.require(app.checkType()).normalizeParameters(settings.parametersFor(app));
        var metadata = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("scenario", PROJECT), Map.entry("inputVersion", "4"),
                Map.entry("checkId", specification.checkId()), Map.entry("checkLabel", app.label()),
                Map.entry("checkTypeLabel", contracts.require(app.checkType()).label()),
                Map.entry("seedType", contracts.require(app.checkType()).seedType().id()),
                Map.entry("checkType", app.checkType()), Map.entry("contractParameters", Jsons.toJson(parameters)),
                Map.entry("eventCode", app.execution().eventCode()),
                Map.entry("workerSelector", Jsons.toJson(AppCheckSettings.selectorMap(app.execution().workerSelector()))),
                Map.entry("salt", specification.salt(date)), Map.entry("saltDate", date.toString())));
        if (specification.country() != null) metadata.put("country", specification.country());
        String name = AppCheckSpecification.taskName(contracts.require(app.checkType()).label(),
                app.label(), specification.country(), clock.instant());
        try {
            return creation.createForRequest(caller, PROJECT, new TaskCreateRequest(specification.requestId(), PROJECT, app.workerGroupId(), 50, 3,
                    app.execution().refill(), name, metadata),
                    specification.fingerprint());
        } catch (TaskCreationUnconfirmedException unknown) {
            throw new RequestFailure(503, "创建结果未确认，请核对已知任务；不会自动重建", unknown.taskId());
        }
    }

    private ProjectTaskQueryService.Entry requireCheck(TaskCaller caller, String taskId) {
        requireRunning();
        var entry = queries.get(caller, PROJECT, taskId);
        if (!isCheckTask(entry)) throw new RequestFailure(400, "只支持批量检查有限任务", taskId);
        return entry;
    }


    public ImportReceipt importSeeds(TaskCaller caller, String taskId, InputStream input) {
        synchronized (gate) {
            requireRunning();
            if (active >= MAX_ACTIVE_IMPORTS) throw new RequestFailure(429, "导入繁忙，请稍后重新发起", taskId);
            active++;
        }
        try {
            return operations.taskMutation(taskId, () -> {
                var entry = requireCheck(caller, taskId);
                if (!"pre_review".equals(entry.scoreBand()))
                    throw new RequestFailure(409, "只有新版本待审核任务支持导入", taskId);
                final AppCheckTaskSnapshot snapshot;
                try { snapshot = AppCheckTaskSnapshot.read(entry.task().workerGroupId(), entry.task().metadata(), contracts); }
                catch (RuntimeException invalid) { throw new RequestFailure(400, "任务执行配置不可用：" + invalid.getMessage(), taskId); }
                long added = 0, existing = 0;
                try (var file = AppCheckSeedFile.read(input, snapshot.contract().seedType(), snapshot.context().country());
                     var reader = Files.newBufferedReader(file.file(), StandardCharsets.UTF_8)) {
                    var batch = new ArrayList<TaskItemRequest>(100);
                    boolean finished = false;
                    while (!finished) {
                        requireRunning();
                        batch.clear();
                        while (batch.size() < 100) {
                            String number = reader.readLine();
                            if (number == null) { finished = true; break; }
                            var payload = snapshot.contract().payload(snapshot.context(), number);
                            batch.add(new TaskItemRequest(snapshot.contract().seedType().messageId(number),
                                    snapshot.eventCode(), payload, 5, null, snapshot.selector()));
                        }
                        if (batch.isEmpty()) break;
                        var effects = data.importFiniteTaskItems(caller, PROJECT, taskId, List.copyOf(batch));
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

    public CsvExport export(TaskCaller caller, String taskId, String filter) {
        if (!Set.of("all", "matched", "unmatched").contains(filter)) throw new RequestFailure(400, "无效导出范围", taskId);
        var task = requireCheck(caller, taskId).task();
        var snapshot = AppCheckTaskSnapshot.read(task.workerGroupId(), task.metadata(), contracts);
        return operations.taskMutation(taskId, () -> {
            Path csv = null;
            boolean complete = false;
            try {
                csv = Files.createTempFile("app-check-results-", ".csv");
                long[] count = {0};
                try (var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                    var headers = new ArrayList<>(List.of("种子", "判定", "检查类型", "执行配置"));
                    if (snapshot.contract().seedType() == AppCheckContract.SeedType.PHONENUMBER) headers.add("地区");
                    headers.addAll(snapshot.contract().exportHeaders());
                    writer.write("\uFEFF" + String.join(",", headers.stream().map(AppCheckTaskService::csvCell).toList()) + "\r\n");
                    exports.forEachResult(caller, taskId, result -> {
                        requireRunning();
                        if (!result.result().succeeded()) return;
                        final AppCheckContract.Result answer;
                        try {
                            answer = interpret(snapshot, new TaskDataService.ResultEntry(result.messageId(), result.item(),
                                    TaskItemResultResponse.from(result.result())));
                        } catch (RuntimeException invalid) { return; }
                        if (filter.equals("matched") && !answer.matched() || filter.equals("unmatched") && answer.matched()) return;
                        var values = new ArrayList<>(List.of(answer.seed(), answer.matched() ? "满足" : "不满足",
                                snapshot.checkTypeLabel(), snapshot.checkLabel()));
                        if (snapshot.contract().seedType() == AppCheckContract.SeedType.PHONENUMBER) values.add(snapshot.context().country());
                        values.addAll(snapshot.contract().exportValues(answer));
                        if (values.size() != headers.size()) throw new IllegalStateException("Contract export column mismatch");
                        try {
                            writer.write(String.join(",", values.stream().map(AppCheckTaskService::csvCell).toList()) + "\r\n");
                            count[0]++;
                        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
                }
                complete = true;
                return new CsvExport(csv, taskId + "-" + filter + ".csv", count[0]);
            } catch (com.xa.mass.server.error.ServerException denied) {
                throw denied;
            } catch (IOException | RuntimeException error) {
                var failure = new RequestFailure(503, "结果导出失败，请重新发起", taskId); failure.initCause(error); throw failure;
            } finally {
                if (!complete) deleteTemporary(csv);
            }
        });
    }

    private static String csvCell(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private static void deleteTemporary(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
    public void transferExport(CsvExport file, OutputStream output) throws IOException { exports.transferAndDelete(file.file(), output); }
    public record CsvExport(Path file, String fileName, long count) { }
    public record ImportReceipt(String taskId, long inputCount, long emptyCount, long duplicateCount, int uniqueCount, long addedCount, long existingCount) { }

    public Map<String, Object> results(TaskCaller caller, String taskId) {
        var task = requireCheck(caller, taskId).task();
        var snapshot = AppCheckTaskSnapshot.read(task.workerGroupId(), task.metadata(), contracts);
        var preview = data.previewTaskResults(caller, taskId);
        return Map.of("taskId", taskId, "results", preview.results().stream()
                .map(row -> resultView(snapshot, row)).toList(), "truncated", preview.truncated());
    }

    private static boolean isCheckTask(ProjectTaskQueryService.Entry entry) {
        return entry.task() != null && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && PROJECT.equals(entry.task().metadata().get("scenario"))
                && PROJECT.equals(entry.task().metadata().get(TaskCaller.INPUT_OWNER));
    }

    private static Map<String, Object> resultView(AppCheckTaskSnapshot snapshot, TaskDataService.ResultEntry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("messageId", entry.messageId()); row.put("resultStatus", entry.result().status().wireValue());
        var item = entry.item();
        if (item != null) {
            try { row.put("seed", snapshot.contract().inputSeed(item.payload())); }
            catch (RuntimeException ignored) { /* Failed executions need no business answer. */ }
        }
        if (entry.result().status() == TaskItemResultStatus.FAILED) return row;
        try {
            var answer = interpret(snapshot, entry);
            row.put("seed", answer.seed()); row.put("matched", answer.matched());
            row.put("details", answer.details());
        } catch (RuntimeException invalid) { row.put("contentError", "Application check result cannot be parsed or associated"); }
        return row;
    }

    private static AppCheckContract.Result interpret(AppCheckTaskSnapshot snapshot, TaskDataService.ResultEntry entry) {
        var item = entry.item();
        if (item == null || !entry.messageId().equals(item.messageId()) || !snapshot.eventCode().equals(item.eventCode()))
            throw new IllegalArgumentException("Item association mismatch");
        var answer = snapshot.contract().result(snapshot.context(), item.payload(), Jsons.parseObject(entry.result().opaqueResultPayload()));
        if (!answer.seed().equals(snapshot.contract().inputSeed(item.payload()))
                || !answer.seed().equals(snapshot.contract().seedType().normalize(answer.seed(), snapshot.context().country()))
                || !entry.messageId().equals(snapshot.contract().seedType().messageId(answer.seed())))
            throw new IllegalArgumentException("Result association mismatch");
        return answer;
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
