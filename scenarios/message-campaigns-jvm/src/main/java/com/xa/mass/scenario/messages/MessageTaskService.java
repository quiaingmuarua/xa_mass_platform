package com.xa.mass.scenario.messages;

import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskCreationUnconfirmedException;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import static com.xa.mass.scenario.messages.MessageSpecification.text;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;

/** Business admission and observation over existing Server Task operations. */
public final class MessageTaskService implements SmartLifecycle, AutoCloseable {
    public static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    private static final int MAX_ACTIVE_IMPORTS = 2;
    private static final Set<String> STAGES = Set.of("SENT", "DELIVERED", "READ", "REPLIED");
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService queries;
    private final TaskCreationService creation;
    private final TaskDataService data;
    private final TaskLifecycleService lifecycle;
    private final MessageScenarioProperties config;
    private final OperationGuard operations;
    private final Object gate = new Object();
    private volatile boolean running;
    private boolean closed;
    private int active, activeImports;

    public MessageTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle, MessageScenarioProperties config, OperationGuard operations) {
        this.projects = projects; this.queries = queries; this.creation = creation;
        this.data = data; this.lifecycle = lifecycle; this.config = Objects.requireNonNull(config);
        this.operations = Objects.requireNonNull(operations);
    }

    @Override public void start() {
        config.workerGroupIds().forEach(group -> projects.requireManagedTaskId(PROJECT, group));
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages is closed");
            running = true;
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }
    private void requireRunning() { if (!running) throw new ProductError(503, "Messages unavailable", null); }

    public Map<String, Object> catalog() {
        requireRunning();
        return Map.of("projectId", PROJECT, "version", "0.3.0-preview", "applications", config.applications(), "countries", COUNTRIES.stream()
                .map(c -> Map.of("id", c)).toList(),
                "limits", Map.of("recipientsPerImport", MessageRecipientFile.MAX_RECIPIENTS, "importFileBytes", MessageRecipientFile.MAX_BYTES));
    }

    public CreatedTask create(Map<String, Object> input) {
        return admitted(null, false, () -> {
            var spec = MessageSpecification.parse(input);
            String workerGroupId = config.findApplication(spec.appId())
                    .orElseThrow(() -> new ProductError(400, "Unsupported appId", null)).workerGroupId();
            try {
                return new CreatedTask(creation.createForRequest(new TaskCreateRequest(PROJECT, workerGroupId, 50, 3,
                        spec.refill(), spec.name(), spec.metadata()), spec.requestId(), spec.fingerprint(workerGroupId)).taskId());
            } catch (TaskCreationUnconfirmedException unknown) {
                throw failure(503, "创建结果未确认，请使用原请求身份核对；不会自动重建", unknown.taskId(), unknown);
            }
        });
    }

    public ImportReceipt importRecipients(String taskId, InputStream input) {
        return admitted(taskId, true, () -> operations.taskMutation(taskId, () -> {
            var entry = requireMessage(taskId, true);
            requireReview(entry);
            MessageSpecification spec;
            try {
                var stored = new LinkedHashMap<String, Object>();
                stored.put("requestId", "stored"); stored.put("name", entry.task().name());
                if (entry.task().metadata().containsKey("senderPhone")) throw new IllegalArgumentException("Unexpected directed sending configuration");
                for (String field : List.of("appId", "recipientCountry", "senderCountry", "body"))
                    if (entry.task().metadata().containsKey(field)) stored.put(field, entry.task().metadata().get(field));
                spec = MessageSpecification.parse(stored);
            } catch (RuntimeException malformed) {
                throw failure(503, "任务配置不可用，请核对任务", taskId, malformed);
            }
            long added = 0, existing = 0;
            try (var file = MessageRecipientFile.read(input, spec.recipientCountry());
                 var reader = Files.newBufferedReader(file.file(), StandardCharsets.UTF_8)) {
                var batch = new ArrayList<TaskItemRequest>(100);
                boolean ended = false;
                while (!ended) {
                    requireRunning(); batch.clear();
                    while (batch.size() < 100) {
                        String number = reader.readLine();
                        if (number == null) { ended = true; break; }
                        String id = MessageSpecification.messageId(taskId, number);
                        batch.add(new TaskItemRequest(id, EVENT, Map.of("campaignId", taskId, "messageId", id,
                                "country", spec.recipientCountry(), "recipientId", number, "body", spec.body()), 5, null, spec.selector()));
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
            } catch (IOException | RuntimeException error) {
                var failure = translate(error, taskId);
                failure.confirmedAddedCount = added; failure.existingCount = existing;
                throw failure;
            }
        }));
    }

    public ActionOutcome approve(String taskId, long expectedCount) {
        return admitted(taskId, false, () -> operations.taskMutation(taskId, () -> {
            var entry = requireMessage(taskId, true);
            requireReview(entry);
            long actual = data.observeItemScoreCounts(List.of(taskId)).get(taskId).total();
            if (actual <= 0 || expectedCount != actual) throw new ProductError(409, "收件人数为空或已变化，请刷新后重新核对", taskId);
            return lifecycle.approve(taskId);
        }));
    }

    public ActionOutcome closeTask(String taskId) {
        return admitted(taskId, false, () -> operations.taskMutation(taskId, () -> {
            requireMessage(taskId, false);
            return lifecycle.close(taskId);
        }));
    }

    private ProjectTaskQueryService.Entry requireMessage(String taskId, boolean newInput) {
        var entry = queries.get(PROJECT, taskId);
        if (!isMessageTask(entry)) throw new ProductError(400, "只支持 Messages 有限任务", taskId);
        if (entry.scoreBand() == null) throw new ProductError(503, "任务状态不可用，请刷新后核对", taskId);
        if (newInput && !"3".equals(entry.task().metadata().get("inputVersion")))
            throw new ProductError(409, "旧输入版本仅支持读取和关闭，不支持导入或启动", taskId);
        return entry;
    }

    private static void requireReview(ProjectTaskQueryService.Entry entry) {
        if (!"pre_review".equals(entry.scoreBand())) throw new ProductError(409, "任务不在待审核状态，请刷新后核对", entry.taskId());
    }

    private <T> T admitted(String taskId, boolean importing, Supplier<T> work) {
        synchronized (gate) {
            requireRunning();
            if (importing && activeImports >= MAX_ACTIVE_IMPORTS) throw new ProductError(429, "导入繁忙，请稍后重试", taskId);
            active++; if (importing) activeImports++;
        }
        try { return work.get(); }
        catch (RuntimeException error) { throw translate(error, taskId); }
        finally { synchronized (gate) { active--; if (importing) activeImports--; gate.notifyAll(); } }
    }

    private static ProductError translate(Throwable error, String taskId) {
        if (error instanceof ProductError known) {
            if (known.taskId != null || taskId == null) return known;
            var associated = failure(known.status, known.getMessage(), taskId, known);
            associated.confirmedAddedCount = known.confirmedAddedCount; associated.existingCount = known.existingCount;
            return associated;
        }
        if (error instanceof ServerException server) {
            return switch (server.errorCode()) {
                case TASK_STATE_CONFLICT, KERNEL_REJECTED_CONFLICT -> failure(409, "任务或请求内容冲突，或有其他操作正在进行，请核对后重试", taskId, error);
                case TASK_NOT_FOUND, TASK_WORKER_GROUP_NOT_FOUND -> failure(404, "任务或 WorkerGroup 不存在", taskId, error);
                case INVALID_TASK_DATA_REQUEST, TASK_OPERATION_NOT_SUPPORTED -> failure(400, "任务不支持该操作或输入无效", taskId, error);
                default -> failure(503, "操作结果未确认，请核对已知任务；不会自动重试", taskId, error);
            };
        }
        return failure(503, "操作结果未确认，已确认写入保留；请核对任务", taskId, error);
    }

    private static ProductError failure(int status, String message, String taskId, Throwable cause) {
        var error = new ProductError(status, message, taskId); error.initCause(cause); return error;
    }

    public record ImportReceipt(String taskId, long inputCount, long emptyCount, long duplicateCount, int uniqueCount,
                                long confirmedAddedCount, long existingCount) { }

    public Map<String, Object> list(int limit) {
        requireRunning();
        if (limit < 1 || limit > 100) throw new ProductError(400, "limit must be in 1..100", null);
        var page = queries.list(PROJECT, limit);
        var ids = page.tasks().stream().filter(MessageTaskService::isMessageTask).map(ProjectTaskQueryService.Entry::taskId).toList();
        var counts = data.observeItemScoreCounts(ids);
        return Map.of("tasks", page.tasks().stream().map(entry -> {
            var row = taskView(entry);
            if (isMessageTask(entry)) addCounts(row, counts.get(entry.taskId()).total(), counts.get(entry.taskId()).countsByTag());
            return row;
        }).toList(), "truncated", page.truncated());
    }

    public Map<String, Object> get(String taskId) {
        requireRunning();
        var entry = queries.get(PROJECT, taskId);
        var task = taskView(entry);
        if (isMessageTask(entry)) {
            var counts = data.observeItemScoreCounts(List.of(taskId)).get(taskId);
            addCounts(task, counts.total(), counts.countsByTag());
        }
        var preview = data.previewTaskResults(taskId);
        return Map.of("task", task, "results", preview.results().stream().map(row -> resultView(taskId, row)).toList(),
                "resultsTruncated", preview.truncated());
    }

    private static boolean isMessageTask(ProjectTaskQueryService.Entry entry) {
        return entry != null && entry.task() != null && PROJECT.equals(entry.task().projectId()) && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && PROJECT.equals(entry.task().metadata().get("scenario"));
    }
    private static Map<String, Object> taskView(ProjectTaskQueryService.Entry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("taskId", entry.taskId()); row.put("createdAtMillis", entry.createdAtMillis()); row.put("state", entry.scoreBand());
        row.put("workerGroupId", entry.task() == null ? null : entry.task().workerGroupId());
        row.put("managed", entry.task() != null && "PARK_WHEN_IDLE".equals(entry.task().idleDisposition()));
        if (entry.task() != null && entry.task().name() != null) row.put("name", entry.task().name());
        if (isMessageTask(entry)) {
            var metadata = entry.task().metadata();
            for (String field : List.of("appId", "recipientCountry", "senderPhone", "body", "inputVersion"))
                if (metadata.containsKey(field)) row.put(field, metadata.get(field));
            row.put("senderCountry", metadata.get("senderCountry"));
        }
        return row;
    }
    private static void addCounts(Map<String, Object> row, long total, Map<Integer, Long> tags) {
        row.put("sendTotal", total); row.put("sentCount", sum(tags, 6)); row.put("deliveredCount", sum(tags, 7));
        row.put("readCount", sum(tags, 8)); row.put("repliedCount", tags.get(9)); row.put("failedCount", tags.get(5));
    }
    private static long sum(Map<Integer, Long> tags, int first) {
        long count = 0; for (int tag = first; tag <= 9; tag++) count += tags.get(tag); return count;
    }
    private static Map<String, Object> resultView(String taskId, TaskDataService.ResultEntry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("messageId", entry.messageId()); row.put("resultStatus", entry.result().status().wireValue());
        var item = entry.item();
        var parameters = item == null ? Map.<String, Object>of() : item.payload();
        if (parameters.get("recipientId") instanceof String recipient) row.put("recipientId", recipient);
        if (entry.result().status() == TaskItemResultStatus.FAILED) {
            row.put("status", "EXECUTION_FAILED"); return row;
        }
        try {
            var snapshot = Jsons.parseObject(entry.result().opaqueResultPayload());
            if (item == null || !EVENT.equals(item.eventCode())
                    || !taskId.equals(parameters.get("campaignId")) || !entry.messageId().equals(parameters.get("messageId"))
                    || !STAGES.contains(snapshot.get("status"))) throw new IllegalArgumentException();
            for (String key : List.of("campaignId", "messageId", "country", "recipientId", "body"))
                if (!Objects.equals(parameters.get(key), snapshot.get(key))) throw new IllegalArgumentException();
            for (String key : List.of("workerId", "phone")) text(snapshot, key, 256);
            if (!(snapshot.get("observedAtMillis") instanceof Number time) || time.longValue() <= 0) throw new IllegalArgumentException();
            if ("REPLIED".equals(snapshot.get("status"))) {
                text(snapshot, "reply", 4096); text(snapshot, "replyRequestId", 128);
            }
            for (String key : List.of("campaignId", "country", "body", "status", "phone", "workerId", "reply", "replyRequestId", "observedAtMillis"))
                if (snapshot.containsKey(key)) row.put(key, snapshot.get(key));
        } catch (RuntimeException invalid) { row.put("contentError", "消息结果内容无法解析或关联不符"); }
        return row;
    }

    @Override public void stop() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (gate) {
            closed = true; running = false;
            while (active > 0 && System.nanoTime() < until) {
                try { TimeUnit.NANOSECONDS.timedWait(gate, Math.max(1L, until - System.nanoTime())); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
    @Override public void close() { stop(); }
    public record CreatedTask(String taskId) {}
    public static final class ProductError extends RuntimeException {
        final int status;
        final String taskId;
        Long confirmedAddedCount, existingCount;
        ProductError(int status, String message, String taskId) { super(message); this.status = status; this.taskId = taskId; }
    }
}
