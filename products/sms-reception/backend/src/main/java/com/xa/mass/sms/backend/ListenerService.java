package com.xa.mass.sms.backend;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import org.springframework.context.SmartLifecycle;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded product records and one shared, paged observation loop. */
public final class ListenerService implements AutoCloseable, SmartLifecycle {
    public static final int CAPACITY = 50_000;
    public static final long SETUP_MILLIS = 30_000;
    public static final long GRACE_MILLIS = 15_000;
    public static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    public static final Map<String, List<Map<String, Object>>> TEMPLATES = Map.of(
            "A", List.of(Map.of("id", "A-code", "priority", 200, "kind", "CODE", "prefix", "[A] ")),
            "B", List.of(Map.of("id", "B-code", "priority", 100, "kind", "CODE", "prefix", "[B] ")),
            "C", List.of(Map.of("id", "C-any", "priority", 0, "kind", "ANY")));
    private static final Set<String> TERMINAL = Set.of("RECEIVED", "CANCELLED", "EXPIRED", "REJECTED", "INTERRUPTED");
    private final WorkerGroupRegistrationService registrations;
    private final TaskCallSubmissionService submissions;
    private final TaskDataService results;
    private final String workerGroupId;
    private final List<String> events;
    private final boolean scheduleObservation;
    private volatile boolean running;
    private boolean closed;
    private final Clock clock;
    private final int limit;
    private String taskId;
    private final Map<String, Record> byId = new LinkedHashMap<>();
    private final Map<String, Record> byRequest = new HashMap<>();
    private final Map<String, Integer> cursors = new HashMap<>();
    private final Object recordsGate = new Object();
    private final Map<String, ArrayBlockingQueue<Command>> commandQueues = new LinkedHashMap<>();
    private ExecutorService commands;
    private final Semaphore commandSlots = new Semaphore(8);
    private ScheduledExecutorService commandPump;
    private ScheduledExecutorService observer;
    private final LongAdder submissionsUnknown = new LongAdder();
    private final LongAdder observationErrors = new LongAdder();
    private final LongAdder repeatRequests = new LongAdder();
    private final String runId = UUID.randomUUID().toString();
    private int nextCommandCountry;

    public ListenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results) {
        this(registrations, submissions, results, Clock.systemUTC(), CAPACITY, true);
    }
    public ListenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results,
            String workerGroupId, List<String> events) {
        this(registrations, submissions, results, Clock.systemUTC(), CAPACITY, true, workerGroupId, events);
    }
    ListenerService(WorkerGroupRegistrationService registrations, TaskCallSubmissionService submissions,
            TaskDataService results, Clock clock, int limit, boolean scheduleObservation) {
        this(registrations, submissions, results, clock, limit, scheduleObservation,
                "demo-sim",
                List.of("extension.worker.sms.listen.start", "extension.worker.sms.listen.cancel"));
    }
    private ListenerService(WorkerGroupRegistrationService registrations, TaskCallSubmissionService submissions,
            TaskDataService results, Clock clock, int limit, boolean scheduleObservation,
            String workerGroupId, List<String> events) {
        this.registrations = registrations;
        this.submissions = submissions;
        this.results = results;
        if (workerGroupId == null || workerGroupId.isBlank()) throw new IllegalArgumentException("WorkerGroup is required");
        this.workerGroupId = workerGroupId;
        this.events = List.copyOf(events);
        this.clock = clock;
        this.limit = limit;
        this.scheduleObservation = scheduleObservation;
        for (String country : COUNTRIES) commandQueues.put(country, new ArrayBlockingQueue<>(256));
    }
    @Override public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("Product run is closed");
        try {
            taskId = registrations.register(workerGroupId, Map.of(), events).taskId();
            commands = Executors.newFixedThreadPool(8);
            commandPump = Executors.newSingleThreadScheduledExecutor();
            observer = Executors.newSingleThreadScheduledExecutor();
            running = true;
            commandPump.scheduleWithFixedDelay(this::dispatchCommands, 10, 10, TimeUnit.MILLISECONDS);
            if (scheduleObservation) observer.scheduleWithFixedDelay(this::observe, 100, 100, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            stop();
            throw error;
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }
    @Override public boolean isAutoStartup() { return true; }
    private void requireRunning() {
        if (!running) throw new ProductError(503, "Product run is not accepting requests");
    }

    public Map<String, Object> catalog() {
        requireRunning();
        return Map.of("version", "0.1.0-preview", "runId", runId, "applications", List.of(
                Map.of("id", "A", "name", "应用 A · 验证码", "templates", TEMPLATES.get("A")),
                Map.of("id", "B", "name", "应用 B · 验证码", "templates", TEMPLATES.get("B")),
                Map.of("id", "C", "name", "应用 C · 全匹配", "templates", TEMPLATES.get("C"))),
                "countries", COUNTRIES.stream().map(c -> Map.of("id", c, "workerGroupId",
                        workerGroupId, "taskId", taskId)).toList(),
                "limits", Map.of("listeners", limit, "listenersPerNumber", 64, "smsRecords", 100_000,
                        "setupMillis", SETUP_MILLIS, "graceMillis", GRACE_MILLIS));
    }

    public Map<String, Object> create(Map<String, Object> input) {
        if (!Set.of("requestId", "applicationId", "country", "listenSeconds").containsAll(input.keySet()))
            throw new ProductError(400, "Unknown request fields");
        String request = text(input, "requestId");
        String app = text(input, "applicationId");
        String country = text(input, "country");
        long seconds = integer(input.getOrDefault("listenSeconds", 60));
        if (!TEMPLATES.containsKey(app) || !COUNTRIES.contains(country) || seconds < 1 || seconds > 300)
            throw new ProductError(400, "Invalid application, country or listening window");
        String key = app + ":" + request;
        synchronized (recordsGate) {
            requireRunning();
            Record previous = byRequest.get(key);
            if (previous != null) {
                if (!previous.country.equals(country) || previous.seconds != seconds)
                    throw new ProductError(409, "requestId already has different content");
                repeatRequests.increment(); return previous.view();
            }
            if (byId.size() >= limit) throw new ProductError(429, "Run listener capacity exhausted");
            Record record = new Record(UUID.randomUUID().toString(), request, app, country, seconds, clock.millis());
            byId.put(record.id, record); byRequest.put(key, record);
            if (!commandQueues.get(country).offer(new Command(record, false, null))) {
                byId.remove(record.id); byRequest.remove(key);
                throw new ProductError(429, "Command admission capacity exhausted");
            }
            return record.view();
        }
    }
    private void dispatchCommands() {
        if (!running) return;
        for (int step = 0; step < COUNTRIES.size(); step++) {
            if (!commandSlots.tryAcquire()) return;
            String country = COUNTRIES.get(nextCommandCountry);
            nextCommandCountry = (nextCommandCountry + 1) % COUNTRIES.size();
            List<Command> batch = new ArrayList<>(100);
            commandQueues.get(country).drainTo(batch, 100);
            if (batch.isEmpty()) { commandSlots.release(); continue; }
            try {
                commands.execute(() -> {
                    try {
                        List<TaskItemRequest> items = batch.stream().map(this::item).toList();
                        submissions.submit(taskId, items);
                    } catch (RuntimeException error) { submissionsUnknown.add(batch.size()); }
                    finally { commandSlots.release(); }
                });
            } catch (RejectedExecutionException stopped) { commandSlots.release(); }
        }
    }
    private TaskItemRequest item(Command command) {
        Record record = command.record;
        Map<String, Object> payload = command.cancel ? Map.of("listenerId", record.id)
                : Map.of("listenerId", record.id, "applicationId", record.app, "country", record.country,
                        "listenSeconds", record.seconds, "setupDeadline", record.setupDeadline, "templates", TEMPLATES.get(record.app));
        return new TaskItemRequest(command.messageId(), "extension.worker.sms.listen."
                + (command.cancel ? "cancel" : "start"), payload, 5,
                command.cancel ? SETUP_MILLIS : Math.max(1, record.setupDeadline - clock.millis()),
                command.cancel ? Map.of("workerId", List.of(command.workerId))
                        : Map.of("worker.country", List.of(record.country)));
    }
    public Map<String, Object> get(String id) { return require(id).view(); }
    public Map<String, Object> page(int offset, int pageSize) {
        if (offset < 0 || pageSize < 1 || pageSize > 1000) throw new ProductError(400, "Invalid page");
        synchronized (recordsGate) {
            return Map.of("total", byId.size(), "offset", offset, "limit", pageSize,
                    "items", byId.values().stream().skip(offset).limit(pageSize).map(Record::view).toList());
        }
    }
    public Map<String, Object> cancel(String id) {
        requireRunning();
        Record record = require(id);
        synchronized (record) {
            if (!record.finalObserved) record.cancelRequested = true;
        }
        dispatchCancel(record);
        return record.view();
    }
    private void dispatchCancel(Record record) {
        synchronized (record) {
            if (!record.cancelRequested || record.finalObserved || record.cancelSent
                    || !(record.snapshot.get("workerId") instanceof String workerId)) return;
            if (commandQueues.get(record.country).offer(new Command(record, true, workerId))) {
                record.cancelSent = true;
            }
        }
    }

    void observe() {
        if (!running) return;
        List<Record> all;
        synchronized (recordsGate) { all = List.copyOf(byId.values()); }
        long now = clock.millis();
        for (String country : COUNTRIES) {
            List<Record> pending = all.stream().filter(r -> r.country.equals(country) && r.needsObservation(now)).toList();
            if (pending.isEmpty()) continue;
            int cursor = cursors.getOrDefault(country, 0) % pending.size();
            int count = Math.min(500, pending.size());
            List<Record> selected = new ArrayList<>(count);
            List<String> messages = new ArrayList<>(count * 2);
            for (int i = 0; i < count; i++) {
                Record record = pending.get((cursor + i) % pending.size());
                selected.add(record); messages.add(record.id);
                synchronized (record) { if (record.cancelSent) messages.add(record.cancelId); }
            }
            cursors.put(country, (cursor + count) % pending.size());
            try {
                Map<String, TaskItemResultResponse> observed = results.loadTaskItemResults(taskId, messages);
                for (Record record : selected) {
                    accept(record, observed.get(record.id));
                    accept(record, observed.get(record.cancelId));
                    dispatchCancel(record);
                }
            } catch (RuntimeException error) { observationErrors.increment(); }
        }
        for (Record record : all) synchronized (record) {
            if (!record.finalObserved && now >= record.observationDeadline) record.unconfirmed = true;
        }
    }

    void accept(Record record, TaskItemResultResponse result) {
        if (result == null || result.status() != TaskItemResultStatus.SUCCEEDED) return;
        String payload = result.opaqueResultPayload();
        Map<String, Object> snapshot;
        try { snapshot = Jsons.parseObject(payload); }
        catch (RuntimeException invalid) { observationErrors.increment(); return; }
        String status = Objects.toString(snapshot.get("status"), "");
        if (!record.id.equals(snapshot.get("listenerId")) || (!status.equals("LISTENING") && !TERMINAL.contains(status)))
            return;
        if (!status.equals("REJECTED") && (!record.app.equals(snapshot.get("applicationId"))
                || !record.country.equals(snapshot.get("country")) || !(snapshot.get("phone") instanceof String)
                || !(snapshot.get("workerId") instanceof String) || !(snapshot.get("startedAt") instanceof Number)
                || !(snapshot.get("expiresAt") instanceof Number))) return;
        if (status.equals("RECEIVED") && !(snapshot.get("sms") instanceof Map<?, ?>)) return;
        synchronized (record) {
            if (record.finalObserved || record.unconfirmed) return;
            record.snapshot = Map.copyOf(snapshot);
            record.finalObserved = TERMINAL.contains(status);
            if (record.establishedObservedAt == 0 && snapshot.containsKey("workerId")) record.establishedObservedAt = clock.millis();
            if (record.finalObserved) record.finalObservedAt = clock.millis();
        }
        dispatchCancel(record);
    }

    public Map<String, Object> metrics() {
        List<Record> records;
        synchronized (recordsGate) { records = List.copyOf(byId.values()); }
        Map<String, Long> statuses = new TreeMap<>();
        List<Long> setup = new ArrayList<>();
        List<Long> delivery = new ArrayList<>();
        int activeObserved = 0;
        for (Record record : records) synchronized (record) {
            statuses.merge(record.status(), 1L, Long::sum);
            if (!record.finalObserved && !record.unconfirmed && "LISTENING".equals(record.snapshot.get("status"))) activeObserved++;
            if (record.establishedObservedAt > 0) setup.add(record.establishedObservedAt - record.createdAt);
            if (record.snapshot.get("sms") instanceof Map<?, ?> sms && sms.get("receivedAt") instanceof Number time)
                delivery.add(Math.max(0, record.finalObservedAt - time.longValue()));
        }
        return Map.of("runId", runId, "requests", records.size(), "statuses", statuses,
                "activeListenersObserved", activeObserved,
                "repeatRequests", repeatRequests.sum(), "submissionUnknown", submissionsUnknown.sum(),
                "observationErrors", observationErrors.sum(), "commandQueue", commandQueues.values().stream().mapToInt(Queue::size).sum(),
                "establishmentLatencyMillis", percentiles(setup), "smsObservationLatencyMillis", percentiles(delivery));
    }
    private static Map<String, Object> percentiles(List<Long> values) {
        Collections.sort(values);
        return Map.of("count", values.size(), "p95", percentile(values, .95), "p99", percentile(values, .99));
    }
    private static long percentile(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * p) - 1));
    }
    Record require(String id) {
        synchronized (recordsGate) {
            Record record = byId.get(id);
            if (record == null) throw new ProductError(404, "Listener not found");
            return record;
        }
    }
    private static String text(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > 128)
            throw new ProductError(400, "Invalid " + key);
        return value;
    }
    private static long integer(Object value) {
        if (!(value instanceof Number n) || n.doubleValue() != n.longValue()) throw new ProductError(400, "Invalid duration");
        return n.longValue();
    }
    @Override public synchronized void stop() {
        if (closed) return;
        closed = true;
        synchronized (recordsGate) { running = false; }
        ExecutorService[] owned = {commandPump, observer, commands};
        for (var executor : owned) if (executor != null) executor.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var executor : owned) if (executor != null) {
            try { executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        commandQueues.values().forEach(Queue::clear);
    }
    @Override public void close() { stop(); }
    private record Command(Record record, boolean cancel, String workerId) {
        String messageId() { return cancel ? record.cancelId : record.id; }
    }
    public static final class ProductError extends RuntimeException {
        final int status;
        ProductError(int status, String message) { super(message); this.status = status; }
    }
    static final class Record {
        final String id, request, app, country, cancelId;
        final long seconds, createdAt, setupDeadline, observationDeadline;
        Map<String, Object> snapshot = Map.of();
        long establishedObservedAt, finalObservedAt;
        boolean cancelRequested, cancelSent, finalObserved, unconfirmed;
        Record(String id, String request, String app, String country, long seconds, long now) {
            this.id = id; this.request = request; this.app = app; this.country = country; this.seconds = seconds;
            this.createdAt = now; setupDeadline = now + SETUP_MILLIS;
            observationDeadline = setupDeadline + seconds * 1000 + GRACE_MILLIS; cancelId = id + "-cancel";
        }
        synchronized boolean needsObservation(long now) { return !finalObserved && !unconfirmed && now < observationDeadline; }
        String status() {
            if (finalObserved) return (String) snapshot.get("status");
            if (unconfirmed) return "UNCONFIRMED";
            if (cancelRequested) return "CANCELLING";
            return snapshot.isEmpty() ? "ESTABLISHING" : "LISTENING";
        }
        synchronized Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>(snapshot);
            view.put("id", id); view.put("requestId", request); view.put("applicationId", app);
            view.put("country", country); view.put("listenSeconds", seconds); view.put("createdAt", createdAt);
            view.put("observationDeadline", observationDeadline); view.put("status", status());
            view.put("cancelRequested", cancelRequested); view.put("establishedObservedAt", establishedObservedAt);
            view.put("finalObservedAt", finalObservedAt);
            return view;
        }
    }
}
