package com.xa.mass.scenario.messages;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Task-backed business view. Only current-run submission deduplication is retained locally. */
public final class MessageTaskService implements SmartLifecycle, AutoCloseable {
    public static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    private static final int MAX_TASKS = 50, MAX_ITEMS = 50_000, MAX_RECIPIENTS = 1000;
    private static final Map<String, String> DIAL_PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");
    private static final java.util.regex.Pattern INTERNATIONAL_NUMBER = java.util.regex.Pattern.compile("\\+[1-9][0-9]{1,14}");
    private static final Set<String> STAGES = Set.of("SENT", "DELIVERED", "READ", "REPLIED");
    private final ProjectDirectory projects;
    private final ProjectTaskQueryService queries;
    private final TaskCreationService creation;
    private final TaskDataService data;
    private final TaskLifecycleService lifecycle;
    private final String workerGroupId;
    private final String runId = UUID.randomUUID().toString();
    private final Object gate = new Object();
    private final Map<String, Submission> requests = new HashMap<>();
    private volatile boolean running;
    private boolean closed;
    private int admittedItems, active;

    public MessageTaskService(ProjectDirectory projects, ProjectTaskQueryService queries, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle, String workerGroupId) {
        this.projects = projects; this.queries = queries; this.creation = creation;
        this.data = data; this.lifecycle = lifecycle; this.workerGroupId = Objects.requireNonNull(workerGroupId);
    }

    @Override public void start() {
        projects.requireManagedTaskId("messages", workerGroupId);
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
        return Map.of("projectId", "messages", "runId", runId, "version", "0.1.0-preview", "countries", COUNTRIES.stream()
                .map(c -> Map.of("id", c, "workerGroupId", workerGroupId)).toList(),
                "limits", Map.of("tasks", MAX_TASKS, "items", MAX_ITEMS, "recipientsPerTask", MAX_RECIPIENTS));
    }

    public CreatedTask create(Map<String, Object> input) {
        Specification specification = Specification.parse(input);
        Submission submission;
        boolean owner;
        synchronized (gate) {
            requireRunning();
            submission = requests.get(specification.requestId());
            owner = submission == null;
            if (!owner && !submission.specification.equals(specification))
                throw new ProductError(409, "requestId has different content", null);
            if (owner) {
                if (active >= 2 || requests.size() >= MAX_TASKS || admittedItems + specification.recipientIds().size() > MAX_ITEMS)
                    throw new ProductError(429, "Messages submission capacity exhausted", null);
                submission = new Submission(specification);
                requests.put(specification.requestId(), submission);
                active++; admittedItems += specification.recipientIds().size();
            }
        }
        if (owner) {
            try { submission.result.complete(submit(specification)); }
            catch (RuntimeException failure) { submission.result.completeExceptionally(failure); }
            finally { synchronized (gate) { active--; gate.notifyAll(); } }
        }
        try { return submission.result.join(); }
        catch (CompletionException failure) { throw (RuntimeException) failure.getCause(); }
    }

    private CreatedTask submit(Specification specification) {
        String taskId = null;
        try {
            var supply = new LinkedHashMap<String, List<String>>();
            var query = new LinkedHashMap<String, Object>();
            var metadata = new LinkedHashMap<String, String>();
            metadata.put("scenario", "messages"); metadata.put("recipientCountry", specification.recipientCountry());
            metadata.put("body", specification.body());
            if (specification.senderCountry() != null) {
                supply.put("worker.country", List.of(specification.senderCountry()));
                query.put("country", List.of(specification.senderCountry()));
                metadata.put("senderCountry", specification.senderCountry());
            }
            if (specification.senderPhone() != null) {
                supply.put("worker.phone", List.of(specification.senderPhone()));
                query.put("phone", specification.senderPhone()); metadata.put("senderPhone", specification.senderPhone());
            }
            requireRunning();
            taskId = creation.create(new TaskCreateRequest("messages", workerGroupId, 50, 3,
                    List.of(RefillTarget.of("messaging", new EligibilityQuery(supply), 100)), specification.name(), metadata)).taskId();
            var selector = new WorkerQuery("worker.messaging.available", query);
            for (int start = 0; start < specification.recipientIds().size(); start += 100) {
                requireRunning();
                var items = new ArrayList<TaskItemRequest>();
                for (String recipient : specification.recipientIds().subList(start, Math.min(start + 100, specification.recipientIds().size()))) {
                    String messageId = UUID.randomUUID().toString();
                    items.add(new TaskItemRequest(messageId, "extension.worker.message.send", Map.of("campaignId", taskId,
                            "messageId", messageId, "country", specification.recipientCountry(), "recipientId", recipient,
                            "body", specification.body()), 5, 60_000L, selector));
                }
                var appended = data.appendFiniteTaskItems(taskId, items);
                if (appended.size() != items.size() || items.stream().anyMatch(item -> appended.get(item.messageId()) == null
                        || !"applied".equals(appended.get(item.messageId()).status().wireValue())))
                    throw new IllegalStateException("Item append is unconfirmed");
            }
            requireRunning();
            lifecycle.approve(taskId);
            return new CreatedTask(taskId);
        } catch (RuntimeException failure) {
            if (failure instanceof TaskCreationUnconfirmedException unknown) taskId = unknown.taskId();
            var unconfirmed = new ProductError(503, "Submission is unconfirmed; do not recreate or retry automatically", taskId);
            unconfirmed.initCause(failure);
            throw unconfirmed;
        }
    }

    public Map<String, Object> list(int limit) {
        requireRunning();
        if (limit < 1 || limit > 100) throw new ProductError(400, "limit must be in 1..100", null);
        var page = queries.list("messages", limit);
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
        var entry = queries.get("messages", taskId);
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
        return entry.task() != null && "CLOSE_WHEN_IDLE".equals(entry.task().idleDisposition())
                && "messages".equals(entry.task().metadata().get("scenario"));
    }
    private static Map<String, Object> taskView(ProjectTaskQueryService.Entry entry) {
        var row = new LinkedHashMap<String, Object>();
        row.put("taskId", entry.taskId()); row.put("createdAtMillis", entry.createdAtMillis()); row.put("state", entry.scoreBand());
        row.put("workerGroupId", entry.task() == null ? null : entry.task().workerGroupId());
        row.put("managed", entry.task() != null && "PARK_WHEN_IDLE".equals(entry.task().idleDisposition()));
        if (entry.task() != null && entry.task().name() != null) row.put("name", entry.task().name());
        if (isMessageTask(entry)) {
            var metadata = entry.task().metadata();
            for (String field : List.of("recipientCountry", "senderPhone", "body"))
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
            if (item == null || !"extension.worker.message.send".equals(item.eventCode())
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
    private record Submission(Specification specification, CompletableFuture<CreatedTask> result) {
        Submission(Specification specification) { this(specification, new CompletableFuture<>()); }
    }
    static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max)
            throw new ProductError(400, "Invalid " + key, null);
        return value;
    }

    record Specification(String requestId, String name, String recipientCountry, String senderCountry, String body,
            List<String> recipientIds, String senderPhone) {
        static Specification parse(Map<String, Object> input) {
            if (input == null || !Set.of("requestId", "name", "recipientCountry", "senderCountry", "body", "recipientIds", "senderPhone").containsAll(input.keySet()))
                throw new ProductError(400, "Unknown message task fields", null);
            String country = text(input, "recipientCountry", 2);
            if (!COUNTRIES.contains(country)) throw new ProductError(400, "Unsupported recipientCountry", null);
            String senderCountry = input.get("senderCountry") == null ? null : text(input, "senderCountry", 2);
            if (senderCountry != null && !COUNTRIES.contains(senderCountry)) throw new ProductError(400, "Unsupported senderCountry", null);
            if (!(input.get("recipientIds") instanceof List<?> recipients) || recipients.isEmpty() || recipients.size() > MAX_RECIPIENTS)
                throw new ProductError(400, "Expected 1..1000 unique international recipient numbers", null);
            String prefix = DIAL_PREFIXES.get(country);
            var normalized = new LinkedHashSet<String>();
            for (int i = 0; i < recipients.size(); i++) {
                if (!(recipients.get(i) instanceof String raw)) throw new ProductError(400, "Invalid recipient at position " + (i + 1), null);
                String number = raw.strip();
                if (!INTERNATIONAL_NUMBER.matcher(number).matches() || !number.startsWith(prefix) || number.length() <= prefix.length())
                    throw new ProductError(400, "Invalid recipient number or country prefix at position " + (i + 1), null);
                if (!normalized.add(number)) throw new ProductError(400, "Duplicate recipient at position " + (i + 1), null);
            }
            Object rawPhone = input.get("senderPhone");
            String phone = rawPhone == null || rawPhone instanceof String value && value.isBlank()
                    ? null : text(input, "senderPhone", 128);
            try { Jsons.parseObject(text(input, "body", 4096)); }
            catch (RuntimeException invalid) { throw new ProductError(400, "body must be a JSON object", null); }
            return new Specification(text(input, "requestId", 128), text(input, "name", 128), country, senderCountry,
                    text(input, "body", 4096), List.copyOf(normalized), phone);
        }
    }


    public static final class ProductError extends RuntimeException {
        final int status;
        final String taskId;
        ProductError(int status, String message, String taskId) { super(message); this.status = status; this.taskId = taskId; }
    }
}
