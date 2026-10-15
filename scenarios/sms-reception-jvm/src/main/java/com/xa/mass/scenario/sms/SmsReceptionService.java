package com.xa.mass.scenario.sms;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.api.v1.contract.task.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.call.TaskRpcPreparationService;
import com.xa.mass.server.task.observation.TaskLeaseProjectionAccess;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.context.request.async.DeferredResult;

/** Consumes RPC Results and retains bounded, process-local business snapshots. */
public final class SmsReceptionService implements SmartLifecycle, AutoCloseable {
    public static final String EVENT = "extension.worker.sms.number.lease";
    public static final String POOL = "sms-reception", FUNCTION = "worker.sms.available";
    public static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    public static final Map<String, List<Map<String, Object>>> TEMPLATES = Map.of(
            "A", List.of(Map.of("id", "A-code", "priority", 200, "kind", "CODE", "prefix", "[A] ")),
            "B", List.of(Map.of("id", "B-code", "priority", 100, "kind", "CODE", "prefix", "[B] ")),
            "C", List.of(Map.of("id", "C-any", "priority", 0, "kind", "ANY")));
    private final TaskRpcPreparationService preparations;
    private final TaskRpcCallService calls;
    private final TaskDataService results;
    private final TaskLeaseProjectionAccess projections;
    private record CacheKey(String taskId, String messageId) { }
    private record CachedResult(TaskItemResultResponse result, boolean successful, long revision) { }
    private final Cache<CacheKey, CachedResult> cache;
    private final String group;
    private final Clock clock;
    private final TaskCaller serviceCaller;
    private final String runId = UUID.randomUUID().toString();
    private final LongAdder requests = new LongAdder(), queries = new LongAdder(), errors = new LongAdder();
    private final Latency acquisition = new Latency(), query = new Latency();
    private volatile String taskId;
    private volatile boolean running;
    private boolean closed;
    public SmsReceptionService(TaskRpcPreparationService preparations, TaskRpcCallService calls, TaskDataService results,
            TaskLeaseProjectionAccess projections, String group, Clock clock, TaskCaller serviceCaller, SmsResultCacheProperties cacheProperties) {
        this(preparations, calls, results, projections, group, clock, serviceCaller, cacheProperties, Ticker.systemTicker());
    }
    SmsReceptionService(TaskRpcPreparationService preparations, TaskRpcCallService calls, TaskDataService results,
            TaskLeaseProjectionAccess projections, String group, Clock clock, TaskCaller serviceCaller,
            SmsResultCacheProperties cacheProperties, Ticker ticker) {
        if (!serviceCaller.isService()) throw new IllegalArgumentException("SMS requires a Project service caller");
        serviceCaller.requireProject("sms");
        this.serviceCaller = serviceCaller;
        this.preparations = preparations; this.calls = calls; this.results = results;
        this.projections = projections; this.group = group; this.clock = clock;
        cache = Caffeine.newBuilder().maximumSize(cacheProperties.maximumSize())
                .expireAfterWrite(cacheProperties.expireAfterWrite()).ticker(ticker).build();
    }
    @Override public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("SMS run is closed");
        taskId = preparations.prepare(serviceCaller, "sms", rpcTaskId(group), new TaskRpcPrepareRequest("sms", group, null, Map.of(), null)).taskId();
        running = true;
    }
    @Override public synchronized void stop() { running = false; closed = true; cache.invalidateAll(); }

    /** SMS owns one shared RPC identity per Group; no caller/user dimension. */
    static String rpcTaskId(String group) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "project-rpc-" + encoder.encodeToString("sms".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(group.getBytes(StandardCharsets.UTF_8));
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void close() { stop(); }
    private void requireRunning() { if (!running) throw new ProductError(503, "SMS unavailable"); }

    public Map<String, Object> catalog() {
        requireRunning();
        return Map.of("runId", runId, "version", "0.1.0-preview", "applications",
                TEMPLATES.keySet().stream().sorted().map(app -> Map.of("id", app, "name", "应用 " + app,
                        "templates", TEMPLATES.get(app))).toList(),
                "countries", COUNTRIES, "limits", Map.of("defaultLeaseSeconds", 60, "maxLeaseSeconds", 300,
                        "setupMillis", 30_000, "waitMillis", 3_000));
    }
    public DeferredResult<Map<String, Object>> lease(TaskCaller caller, Map<String, Object> input) {
        caller.requirePermission("sms.use"); caller.requireProject("sms");
        requireRunning();
        if (!Set.of("applicationId", "country", "leaseSeconds").containsAll(input.keySet()))
            throw new ProductError(400, "Unknown request fields");
        String app = text(input, "applicationId"), country = text(input, "country");
        Object duration = input.getOrDefault("leaseSeconds", 60);
        if (!(duration instanceof Number seconds) || seconds.doubleValue() != seconds.longValue()
                || seconds.longValue() < 1 || seconds.longValue() > 300
                || !TEMPLATES.containsKey(app) || !COUNTRIES.contains(country))
            throw new ProductError(400, "Invalid application, country or lease duration");
        String id = "sms-" + UUID.randomUUID();
        long started = System.nanoTime();
        var payload = Map.<String, Object>of("messageId", id, "applicationId", app, "country", country,
                "leaseSeconds", seconds.longValue(), "setupDeadline", clock.millis() + 30_000,
                "templates", TEMPLATES.get(app));
        var item = new TaskItemRequest(id, EVENT, payload, 5, 30_000L,
                new WorkerQuery(FUNCTION, Map.of("partition", app, "country", country)));
        requests.increment();
        try {
            return calls.call(serviceCaller, "sms", taskId, new TaskRpcCallRequest(List.of(item), 3_000L), rows -> {
                var response = observe(id, rows.get(id));
                acquisition.add((System.nanoTime() - started) / 1_000_000);
                return response;
            });
        } catch (RuntimeException failure) { errors.increment(); throw failure; }
    }
    public Map<String, Object> get(TaskCaller caller, String messageId) {
        caller.requirePermission("sms.use"); caller.requireProject("sms");
        requireRunning();
        if (messageId == null || messageId.isBlank() || messageId.length() > 256)
            throw new ProductError(400, "Invalid messageId");
        queries.increment();
        long started = System.nanoTime();
        try { return observe(messageId, results.takeTaskItemResults(serviceCaller, "sms", taskId, List.of(messageId)).get(messageId)); }
        catch (RuntimeException failure) { errors.increment(); throw failure; }
        finally { query.add((System.nanoTime() - started) / 1_000_000); }
    }
    private Map<String, Object> observe(String id, TaskItemResultResponse result) {
        requireRunning();
        var key = new CacheKey(taskId, id);
        CachedResult retained;
        if (result == null || result.status() == TaskItemResultStatus.NOT_OBSERVED) retained = cache.getIfPresent(key);
        else {
            var value = result.status() == TaskItemResultStatus.SUCCEEDED ? snapshot(id, result) : Map.<String, Object>of();
            boolean successful = !value.isEmpty() && !"REJECTED".equals(value.get("status"));
            var candidate = new CachedResult(result, successful, successful ? ((Number) value.get("revision")).longValue() : -1);
            boolean changed = false;
            // A compute returning the old value still refreshes Caffeine's write expiry.
            // Conditional insertion/replacement leaves equal/older observations read-only.
            for (;;) {
                retained = cache.getIfPresent(key);
                if (retained != null && (!candidate.successful()
                        || retained.successful() && candidate.revision() <= retained.revision())) break;
                boolean replaced = retained == null ? cache.asMap().putIfAbsent(key, candidate) == null
                        : cache.asMap().replace(key, retained, candidate);
                if (replaced) { retained = candidate; changed = true; break; }
            }
            if (!running) { cache.invalidate(key); throw new ProductError(503, "SMS unavailable"); }
            if (changed && successful) projections.acceptResults(serviceCaller, taskId, Map.of(id, result));
        }
        return view(id, retained == null ? null : retained.result(), clock.millis());
    }
    static Map<String, Object> view(String id, TaskItemResultResponse result, long now) {
        if (result == null || result.status() == TaskItemResultStatus.NOT_OBSERVED)
            return Map.of("messageId", id, "status", "NOT_OBSERVED");
        if (result.status() != TaskItemResultStatus.SUCCEEDED)
            return Map.of("messageId", id, "status", "FAILED");
        Map<String, Object> snapshot = snapshot(id, result);
        if (snapshot.isEmpty()) return Map.of("messageId", id, "status", "FAILED", "reason", "Invalid number result");
        var response = new LinkedHashMap<>(snapshot);
        if ("REJECTED".equals(snapshot.get("status"))) { response.put("status", "FAILED"); return response; }
        boolean active = now < ((Number) snapshot.get("leaseUntil")).longValue();
        response.put("leaseActive", active);
        response.put("status", snapshot.containsKey("sms") ? "RECEIVED" : active ? "WAITING" : "EXPIRED");
        return Collections.unmodifiableMap(response);
    }
    private static Map<String, Object> snapshot(String id, TaskItemResultResponse result) {
        try {
            var value = Jsons.parseObject(result.opaqueResultPayload());
            if (!id.equals(value.get("messageId"))) return Map.of();
            if ("REJECTED".equals(value.get("status"))) return value;
            if (!Set.of("WAITING", "RECEIVED").contains(value.get("status"))
                    || !TEMPLATES.containsKey(value.get("applicationId")) || !COUNTRIES.contains(value.get("country"))) return Map.of();
            text(value, "phoneNumber"); text(value, "workerId");
            if (!(value.get("leaseUntil") instanceof Number deadline) || deadline.longValue() < 1
                    || deadline.doubleValue() != deadline.longValue()) return Map.of();
            if (!(value.get("revision") instanceof Number revision) || revision.longValue() < 0
                    || revision.doubleValue() != revision.longValue()) return Map.of();
            return value;
        } catch (RuntimeException invalid) { return Map.of(); }
    }
    public static Map<Coordinate, Long> leaseProjection(Map<String, TaskItemResultResponse> results) {
        var updates = new LinkedHashMap<Coordinate, Long>();
        results.forEach((id, result) -> {
            if (result == null || result.status() != TaskItemResultStatus.SUCCEEDED) return;
            var value = snapshot(id, result);
            if (value.isEmpty() || "REJECTED".equals(value.get("status"))) return;
            updates.merge(new Coordinate((String) value.get("phoneNumber"), (String) value.get("applicationId"),
                    (String) value.get("workerId")), ((Number) value.get("leaseUntil")).longValue(), Math::max);
        });
        return Collections.unmodifiableMap(updates);
    }
    public Map<String, Object> metrics(TaskCaller caller) {
        caller.requirePermission("sms.use"); caller.requireProject("sms");
        return Map.of("runId", runId, "requests", requests.sum(), "queries", queries.sum(), "errors", errors.sum(),
                "acquisitionLatencyMillis", acquisition.view(), "queryLatencyMillis", query.view(), "projection", projections.metrics(),
                "cacheSize", cache.estimatedSize());
    }
    private static String text(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > 256)
            throw new ProductError(400, "Invalid " + key);
        return value;
    }
    public static final class ProductError extends RuntimeException {
        final int status;
        ProductError(int status, String message) { super(message); this.status = status; }
    }
    private static final class Latency {
        private final long[] samples = new long[2048];
        private long count;
        synchronized void add(long millis) { samples[(int) (count++ % samples.length)] = millis; }
        synchronized Map<String, Object> view() {
            int size = (int) Math.min(count, samples.length);
            long[] sorted = Arrays.copyOf(samples, size); Arrays.sort(sorted);
            return Map.of("count", count, "p95", size == 0 ? 0 : sorted[(int) Math.ceil(size * .95) - 1],
                    "p99", size == 0 ? 0 : sorted[(int) Math.ceil(size * .99) - 1]);
        }
    }
}
