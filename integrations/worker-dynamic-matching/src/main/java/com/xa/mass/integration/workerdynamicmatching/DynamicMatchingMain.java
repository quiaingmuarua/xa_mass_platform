package com.xa.mass.integration.workerdynamicmatching;

import static com.xa.mass.integration.workerdynamicmatching.ProofApi.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** External-process proof. No implementation imports or direct Owner calls. */
public final class DynamicMatchingMain {
    static final String STRING = "scenario-string-utils-workers";
    static final String PHONE = "scenario-phone-number-workers";
    static final String ADAPTER = "scenario-websocket";
    static final String EVENT = "extension.worker.lab.execution-witness";
    static final int GROUP_SIZE = 500, TARGET_COUNT = 100, PAGE_SIZE = 100;
    private final ProofApi runtime;
    private final ProofApi lab;
    private final Path output;
    private final List<Worker> workers = new ArrayList<>();
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final Map<String, Task> tokenTasks = new ConcurrentHashMap<>();
    private final Map<String, Worker> coordinates = new HashMap<>();
    private final Map<String, Worker> workersById = new HashMap<>();
    private final Map<Long, Map<String, Object>> entered = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ExecutorService readers = Executors.newFixedThreadPool(4);
    private final AtomicLong adapterReads = new AtomicLong(), serverReads = new AtomicLong();
    private final AtomicLong temporaryReads = new AtomicLong(), mutations = new AtomicLong(), accepted = new AtomicLong();
    private final AtomicLong platformMutations = new AtomicLong();
    private final List<Map<String, Object>> checkpoints = new CopyOnWriteArrayList<>();
    private volatile long workloadDeadline = Long.MAX_VALUE;
    private volatile long dynamicDeadline = Long.MAX_VALUE;
    private volatile String stage = "bootstrap";
    private long cursor;
    private long workloadStarted;
    private long dynamicStarted, dynamicElapsedMillis;

    private DynamicMatchingMain(Map<String, String> args) throws Exception {
        runtime = new ProofApi(args.get("server"));
        lab = new ProofApi(args.get("lab"));
        output = Path.of(args.get("output"));
        for (Object raw : array(Jsons.parseObject(Files.readString(Path.of(args.get("spec")))).get("workers"))) {
            var row = object(raw);
            Worker w = new Worker(text(row.get("group")), text(row.get("key")), strings(row.get("properties")));
            require(coordinates.put(w.coordinate(), w) == null, "duplicate-lab-coordinate");
            workers.add(w);
        }
        require(workers.size() == 2 * GROUP_SIZE && targets().size() == TARGET_COUNT, "fixed-world-size");
        for (String group : List.of(STRING, PHONE))
            require(workers.stream().filter(w -> w.group.equals(group)).count() == GROUP_SIZE, "fixed-group-size");
    }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String arg : arguments) {
            String[] pair = arg.substring(2).split("=", 2);
            require(pair.length == 2 && args.put(pair[0], pair[1]) == null, "arguments");
        }
        require(args.keySet().equals(Set.of("server", "lab", "spec", "output", "ready", "continue")), "arguments");
        DynamicMatchingMain proof = new DynamicMatchingMain(args);
        boolean success = false;
        try {
            proof.bootstrap();
            Files.writeString(Path.of(args.get("ready")), "ready\n");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            proof.until(deadline, () -> Files.exists(Path.of(args.get("continue"))), "runner-audit-handshake");
            proof.run();
            success = true;
        } catch (Exception error) {
            proof.failure.compareAndSet(null, error);
            throw error;
        } finally {
            proof.stopping.set(true);
            proof.readers.shutdownNow();
            proof.readers.awaitTermination(5, TimeUnit.SECONDS);
            proof.save(success);
            proof.runtime.close();
            proof.lab.close();
        }
    }

    private void bootstrap() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        until(deadline, () -> {
            Map<String, Object> body = lab.call("GET", "/lab/v1/workers", null, true);
            Set<String> ids = new HashSet<>(), seen = new HashSet<>();
            for (Object raw : array(body.get("workers"))) {
                var row = object(raw);
                String coordinate = text(row.get("workerGroupId")) + "/" + text(row.get("labWorkerKey"));
                Worker w = coordinates.get(coordinate);
                require(w != null && seen.add(coordinate), "lab-inventory-drift");
                if (!(row.get("workerId") instanceof String id)) return false;
                require(ids.add(id), "duplicate-worker-identity");
                w.id = id;
                requireRun(w, row);
            }
            return seen.size() == workers.size();
        }, "initial-identities");
        workers.forEach(w -> workersById.put(w.id, w));
        // First facts may legitimately be absent after Prepare. Do not install an empty baseline.
        until(deadline, () -> observeRuntime(true) && observeAdapter(true), "initial-facts");
        for (Worker w : workers) platform(w, "yes");
        until(deadline, () -> observeRuntime(false) && observeAdapter(false), "initial-platform-facts");
        checkWorld(true);
        launch(this::observeAdapter);
        launch(this::observeRuntime);
        launch(this::readJournal);
        launch(this::readResults);
    }

    private void run() throws Exception {
        phase("seed-workload");
        createTask("background-a", STRING, rule("A", false, true), 50_000, 1_000, false, "A", true);
        createTask("background-b", STRING, rule("B", false, true), 50_000, 1_000, false, "B", true);
        createTask("background-phone", PHONE, Map.of(), 50_000, 1_000, false, null, true);
        workloadStarted = System.nanoTime();
        workloadDeadline = workloadStarted + TimeUnit.SECONDS.toNanos(600);
        for (Task t : tasks) approve(t);
        until(workloadDeadline, () -> tasks.stream().allMatch(t -> !t.succeeded.isEmpty() && t.lastEntry.get() > 0),
                "initial-loaded-execution");
        dynamicStarted = System.nanoTime();
        dynamicDeadline = dynamicStarted + TimeUnit.SECONDS.toNanos(180);

        Task b = witness("b-before-change", "B", false);
        blocked(b);
        loaded();
        phase("a-to-b-burst");
        b.admit();
        long ar = adapterReads.get(), sr = serverReads.get(), progress = backgroundSuccesses();
        long sent = System.nanoTime();
        try (ExecutorService writers = Executors.newFixedThreadPool(4)) {
            for (int round = 1; round <= 8; round++) {
                long roundStart = System.nanoTime();
                List<Future<?>> pending = new ArrayList<>();
                for (Worker w : targets()) {
                    int n = round;
                    pending.add(writers.submit(() -> {
                        try { mutate(w, "PATCH", Map.of("proofPool", "B", "sequence", "" + n,
                                "mirror", "" + n, "delta-" + n, "" + n)); }
                        catch (Exception error) { throw new CompletionException(error); }
                    }));
                }
                for (Future<?> future : pending) future.get();
                if (round < 8) {
                    long left = TimeUnit.MILLISECONDS.toNanos(500) - (System.nanoTime() - roundStart);
                    if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
                }
            }
            sent = targets().stream().mapToLong(w -> w.lastMutation).max().orElseThrow();
        }
        require(adapterReads.get() > ar && serverReads.get() > sr, "no-observation-overlap");
        require(backgroundSuccesses() > progress, "no-business-progress-during-burst");
        awaitProperties(sent);
        succeeded(b);

        Task a = witness("a-waits-for-return", "A", false);
        blocked(a);
        loaded(); phase("pool-removed");
        sent = replaceTargets(null);
        awaitProperties(sent);
        Task absent = witness("missing-pool", null, true);
        succeeded(absent);

        loaded(); phase("platform-disabled");
        sent = replaceTargets("B"); awaitProperties(sent);
        for (Worker w : targets()) platform(w, "no");
        awaitProperties(targets().stream().mapToLong(w -> w.lastMutation).max().orElseThrow());
        Task gated = witness("platform-gated-b", "B", false);
        blocked(gated);
        loaded(); phase("platform-enabled");
        gated.admit();
        for (Worker w : targets()) platform(w, "yes");
        awaitProperties(targets().stream().mapToLong(w -> w.lastMutation).max().orElseThrow());
        succeeded(gated);

        loaded(); phase("return-a");
        a.admit();
        sent = replaceTargets("A"); awaitProperties(sent);
        succeeded(a);
        require(System.nanoTime() < dynamicDeadline, "dynamic-deadline");
        dynamicElapsedMillis = millisSince(dynamicStarted);
        dynamicDeadline = Long.MAX_VALUE;
        phase("drain-workload");
        until(workloadDeadline, () -> tasks.stream().allMatch(Task::complete), "all-success-results");
        until(workloadDeadline, this::allTerminal, "terminal-tasks");
        until(workloadDeadline, () -> tasks.stream().allMatch(t -> t.completed.containsAll(t.tokens)), "execution-completion-witnesses");
        checkWorld(false);
        phase("pending-runner-audit");
    }

    private Map<String, Object> rule(String pool, boolean target, boolean enabled) {
        var rule = new LinkedHashMap<String, Object>();
        rule.put("worker.proofPool", pool == null ? Map.of("$exists", false) : Map.of("$eq", pool));
        if (target) rule.put("worker.proofTarget", Map.of("$eq", "yes"));
        if (enabled) rule.put("platform.proofEnabled", Map.of("$eq", "yes"));
        return rule;
    }

    private Task witness(String label, String pool, boolean admitted) throws Exception {
        Task t = createTask(label, STRING, rule(pool, true, true), TARGET_COUNT, 100, true, pool, admitted);
        approve(t);
        return t;
    }

    private Task createTask(String label, String group, Map<String, Object> rule, int count, int delay,
                            boolean witness, String pool, boolean admitted) throws Exception {
        var response = runtime.call("POST", "/api/v1/tasks", Map.of("workerGroupId", group,
                "allocationRule", rule, "priority", witness ? 10 : 50,
                "maximumCandidateWorkers", witness ? TARGET_COUNT : GROUP_SIZE, "maxRetryTimes", 3), false);
        String id = text(response.get("taskId"));
        Set<String> allowed = new HashSet<>();
        for (Worker w : workers) {
            if (w.group.equals(group) && (witness ? w.target() : pool == null || w.target() || pool.equals(w.baseline.get("proofPool"))))
                allowed.add(w.coordinate());
        }
        Task t = new Task(label, id, group, witness, Set.copyOf(allowed), admitted);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String token = UUID.randomUUID().toString();
            t.tokens.add(token);
            tokenTasks.put(token, t);
            items.add(Map.of("messageId", token, "eventCode", EVENT,
                    "payload", Map.of("probeToken", token, "delayMillis", delay)));
        }
        for (int offset = 0; offset < items.size(); offset += 100) {
            var page = items.subList(offset, Math.min(offset + 100, items.size()));
            var appended = runtime.call("POST", "/api/v1/tasks/" + id + "/items", page, false);
            require(appended.size() == page.size(), "append-result-count");
            for (var item : page) require("applied".equals(object(appended.get(item.get("messageId"))).get("status")), "item-append-rejected");
        }
        tasks.add(t);
        return t;
    }

    private void approve(Task t) throws Exception {
        if (t.mayExecute.get()) t.admit();
        var result = runtime.call("POST", "/api/v1/tasks/" + t.id + "/approve", null, false);
        require("applied".equals(result.get("status")), "task-approval-rejected");
        t.approved = true;
    }

    private void blocked(Task t) throws Exception {
        phase(t.label + "-blocked");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            healthy();
            require(t.succeeded.isEmpty() && t.entries.get() == 0, "blocked-task-executed");
            Thread.sleep(100);
        }
        // Independent fresh observations close the negative window; reader lag is not evidence of absence.
        readResults(t);
        synchronized (entered) { readJournal(); }
        require(t.succeeded.isEmpty() && t.entries.get() == 0, "blocked-task-executed");
        checkpoints.add(Map.of("stage", stage, "negativeWindowMillis", 3_000, "executions", 0));
    }

    private void succeeded(Task t) throws Exception {
        until(Math.min(dynamicDeadline, t.eligibleSince + TimeUnit.SECONDS.toNanos(60)),
                () -> t.complete() && t.completed.containsAll(t.tokens), "witness-" + t.label);
        checkpoints.add(Map.of("stage", t.label, "succeeded", t.succeeded.size(), "attempts", t.entries.get(),
                "executorsSha256", digest(t.executors.stream().sorted().toList()), "executorCheck", true,
                "eligibleToCompletionMillis", millisSince(t.eligibleSince)));
    }

    private void loaded() throws Exception {
        healthy();
        Map<String, String> bands = taskBands();
        require(tasks.stream().anyMatch(t -> !t.witness && t.group.equals(STRING) && !"terminal".equals(bands.get(t.id))
                && !t.complete() && System.nanoTime() - t.lastEntry.get() < TimeUnit.SECONDS.toNanos(10)), "not-under-load");
        checkpoints.add(Map.of("stage", stage, "loadedPrecondition", true, "backgroundSucceeded", backgroundSuccesses()));
    }

    private long replaceTargets(String pool) throws Exception {
        long sent = 0;
        for (Worker w : targets()) {
            var properties = new LinkedHashMap<>(w.baseline);
            if (pool == null) properties.remove("proofPool"); else properties.put("proofPool", pool);
            sent = mutate(w, "PUT", properties);
        }
        return sent;
    }

    private long mutate(Worker w, String method, Map<String, String> supplied) throws Exception {
        loaded();
        var next = new LinkedHashMap<String, String>();
        if (method.equals("PATCH")) next.putAll(w.expected);
        next.putAll(supplied);
        w.expected = Map.copyOf(next);
        w.history.add(w.expected); // In-flight reads are judged against this request, whose failure fails the proof.
        long sent = w.lastMutation = System.nanoTime();
        mutations.incrementAndGet();
        var response = lab.call(method, w.path() + ":properties", supplied, false);
        require(response.keySet().equals(Set.of("persisted", "sendAccepted"))
                && Boolean.TRUE.equals(response.get("persisted")) && Boolean.TRUE.equals(response.get("sendAccepted")), "properties-not-accepted");
        accepted.incrementAndGet();
        return sent;
    }

    private void platform(Worker w, String enabled) throws Exception {
        if (workloadStarted != 0) { loaded(); platformMutations.incrementAndGet(); }
        w.platform = Map.of("proofEnabled", enabled);
        w.platformHistory.add(w.platform);
        w.lastMutation = System.nanoTime();
        var result = runtime.call("PATCH", "/api/v1/worker-groups/" + w.group + "/workers/" + w.id
                + "/platform-properties", w.platform, false);
        require(Set.of("applied", "unchanged").contains(result.get("status")), "platform-write-rejected");
    }

    private void awaitProperties(long sent) throws Exception {
        long end = Math.min(dynamicDeadline, sent + TimeUnit.SECONDS.toNanos(5));
        long[] observed = {-1, -1};
        until(end, () -> {
            if (observed[0] < 0 && workers.stream().allMatch(w -> w.expected.equals(w.adapter))) observed[0] = millisSince(sent);
            if (observed[1] < 0 && workers.stream().allMatch(w -> w.expected.equals(w.server) && w.platform.equals(w.serverPlatform))) observed[1] = millisSince(sent);
            return observed[0] >= 0 && observed[1] >= 0;
        }, "properties-checkpoint");
        checkWorld(false);
        checkpoints.add(Map.of("stage", stage, "adapterMillis", observed[0], "serverMillis", observed[1],
                "propertiesSha256", digest(workers.stream().map(w -> new TreeMap<>(w.expected)).toList()), "controlsUnchanged", true));
    }

    private boolean observeRuntime(boolean initial) throws Exception {
        boolean current = true;
        for (String group : List.of(STRING, PHONE)) {
            var result = runtime.call("POST", "/api/v1/runtime-view/worker-groups/" + group + "/workers:preview", GROUP_SIZE, true);
            require(number(result.get("unreadableCount")) == 0, "unreadable-worker");
            var rows = array(result.get("workers"));
            require(rows.size() == GROUP_SIZE, "runtime-sample-count");
            Set<String> seen = new HashSet<>();
            for (Object raw : rows) {
                var row = object(raw);
                Worker w = workersById.get(text(row.get("workerId")));
                require(w != null, "unexpected-runtime-worker");
                require(w.group.equals(group) && group.equals(row.get("workerGroupId"))
                        && ADAPTER.equals(row.get("endpointManagerId")) && seen.add(w.id), "runtime-binding-drift");
                Map<String, String> properties = strings(row.get("workerProperties"));
                if (initial && properties.isEmpty()) { current = false; continue; }
                ProofAssertions.snapshot(properties, w.history);
                var platform = strings(row.get("platformProperties"));
                ProofAssertions.snapshot(platform, w.platformHistory);
                w.server = properties; w.serverPlatform = platform;
                current &= properties.equals(w.expected) && platform.equals(w.platform);
            }
        }
        serverReads.incrementAndGet();
        return current;
    }
    private void observeRuntime() throws Exception { observeRuntime(false); }

    private boolean observeAdapter(boolean initial) throws Exception {
        boolean current = true;
        for (List<Worker> page : workerPages(workers)) current &= observeAdapterPage(page, initial);
        adapterReads.incrementAndGet();
        return current;
    }

    private boolean observeAdapterPage(List<Worker> page, boolean initial) throws Exception {
        var response = runtime.call("POST", "/api/v1/worker-delivery/endpoint-managers/" + ADAPTER + "/direct-calls",
                Map.of("messageType", "platform.adapter.worker-properties.snapshot", "waitTimeoutMillis", 1_000,
                        "opaquePayload", Jsons.toJson(Map.of("workerIds", page.stream().map(w -> w.id).toList()))), true);
        var result = object(object(response.get("results")).get(ADAPTER));
        if ("unobserved".equals(result.get("status")) && "timeout".equals(result.get("reason"))) throw new TemporaryRead();
        require("observed".equals(result.get("status")) && "platform.adapter.command.succeeded".equals(result.get("messageType")), "adapter-snapshot-rejected");
        var snapshots = object(Jsons.parseObject(text(result.get("opaqueResultPayload"))).get("propertiesByWorkerId"));
        require(snapshots.keySet().equals(new HashSet<>(page.stream().map(w -> w.id).toList())), "adapter-worker-set");
        boolean current = true;
        for (Worker w : page) {
            var row = object(snapshots.get(w.id));
            if (initial && row.get("properties") == null) { current = false; continue; }
            require(row.get("updatedAtMillis") instanceof Number n && n.longValue() > 0, "adapter-baseline-missing");
            var properties = strings(row.get("properties"));
            ProofAssertions.snapshot(properties, w.history);
            w.adapter = properties;
            current &= properties.equals(w.expected);
        }
        return current;
    }
    private void observeAdapter() throws Exception { observeAdapter(false); }

    private void checkWorld(boolean initial) throws Exception {
        try (ExecutorService checks = Executors.newFixedThreadPool(4)) {
            List<Future<?>> pending = new ArrayList<>();
            for (Worker w : workers) pending.add(checks.submit(() -> {
                try {
                    var row = retryRead(() -> lab.call("GET", w.path(), null, true));
                    requireRun(w, row);
                    require(w.expected.equals(strings(row.get("workerProperties"))), "lab-properties-drift");
                } catch (Exception error) {
                    failure.compareAndSet(null, error);
                    throw new CompletionException(error);
                }
            }));
            for (Future<?> check : pending) check.get();
        }
        for (List<Worker> page : workerPages(workers)) {
            var network = object(retryRead(() -> runtime.call("POST", "/api/v1/runtime-view/endpoint-managers/" + ADAPTER
                    + "/workers:network-observe", page.stream().map(w -> w.id).toList(), true)).get("statesByWorkerId"));
            require(network.keySet().equals(new HashSet<>(page.stream().map(w -> w.id).toList()))
                    && network.values().stream().allMatch("connected"::equals), "worker-connection-drift");
        }
        if (initial) for (String group : List.of(STRING, PHONE)) {
            for (List<Worker> page : workerPages(workers.stream().filter(w -> w.group.equals(group)).toList())) {
                var scores = object(retryRead(() -> runtime.call("POST", "/api/v1/runtime-view/worker-groups/" + group
                        + "/workers:scheduling-observe", page.stream().map(w -> w.id).toList(), true)).get("statesByWorkerId"));
                require(scores.keySet().equals(new HashSet<>(page.stream().map(w -> w.id).toList()))
                        && scores.values().stream().allMatch(s -> Set.of("hot-score-overdue", "held-hot").contains(s)), "initial-hot-activation");
            }
        }
    }

    private static List<List<Worker>> workerPages(List<Worker> source) {
        List<List<Worker>> pages = new ArrayList<>();
        for (int offset = 0; offset < source.size(); offset += PAGE_SIZE)
            pages.add(source.subList(offset, Math.min(offset + PAGE_SIZE, source.size())));
        return pages;
    }

    private static void requireRun(Worker w, Map<String, Object> row) {
        ProofAssertions.identity(w.id, row.get("workerId"));
        require(w.group.equals(row.get("workerGroupId")) && w.key.equals(row.get("labWorkerKey"))
                && "RUNNING".equals(row.get("runtimeState")) && "RUNNING".equals(row.get("desiredState")), "worker-run-drift");
    }

    private void readJournal() throws Exception {
        synchronized (entered) {
            List<?> records;
            do {
                var page = lab.call("GET", "/lab/v1/execution-witnesses?after=" + cursor + "&limit=100", null, true);
                require(Boolean.FALSE.equals(page.get("overflowed")), "execution-journal-overflow");
                records = array(page.get("records"));
                require(records.size() <= 100, "journal-page-bound");
                for (Object raw : records) {
                    var row = object(raw);
                    require(number(row.get("sequence")) == ++cursor, "journal-sequence-gap");
                    String token = text(row.get("probeToken"));
                    Task t = tokenTasks.get(token);
                    require(t != null, "unknown-execution-token");
                    String coordinate = text(row.get("workerGroupId")) + "/" + text(row.get("labWorkerKey"));
                    ProofAssertions.executor(coordinate, t.allowed, t.mayExecute.get());
                    long attempt = number(row.get("attemptId"));
                    String state = text(row.get("state"));
                    if (state.equals("ENTERED")) {
                        require(attempt == cursor && entered.putIfAbsent(attempt, row) == null, "duplicate-attempt-entry");
                        t.entries.incrementAndGet(); t.lastEntry.set(System.nanoTime()); t.executors.add(coordinate);
                    } else {
                        require(state.equals("COMPLETED"), "handler-failed");
                        var start = entered.remove(attempt);
                        require(start != null && token.equals(start.get("probeToken"))
                                && row.get("workerGroupId").equals(start.get("workerGroupId"))
                                && row.get("labWorkerKey").equals(start.get("labWorkerKey")), "attempt-completion-mismatch");
                        t.completed.add(token);
                    }
                }
                require(number(page.get("nextCursor")) == cursor, "journal-cursor-mismatch");
            } while (records.size() == 100 && !stopping.get());
        }
    }

    private void readResults() throws Exception { for (Task t : tasks) readResults(t); }
    private void readResults(Task t) throws Exception {
        if (!t.approved) return;
        List<String> pending = t.tokens.stream().filter(token -> !t.succeeded.contains(token)).toList();
        for (int offset = 0; offset < pending.size(); offset += 1_000) {
            var page = pending.subList(offset, Math.min(offset + 1_000, pending.size()));
            var results = runtime.call("POST", "/api/v1/tasks/" + t.id + "/results:load", page, true);
            require(results.keySet().equals(new HashSet<>(page)), "result-identity-set");
            for (String token : page) {
                var result = object(results.get(token));
                String status = text(result.get("status"));
                require(Set.of("succeeded", "not_observed").contains(status), "failed-or-invalid-result");
                require(result.keySet().equals(status.equals("succeeded") ? Set.of("status", "opaqueResultPayload") : Set.of("status")), "result-shape");
                if (status.equals("succeeded")) {
                    require(result.get("opaqueResultPayload") instanceof String, "result-payload-shape");
                    require(t.mayExecute.get(), "result-before-eligibility-change");
                    t.succeeded.add(token);
                }
            }
        }
    }

    private Map<String, String> taskBands() throws Exception {
        var result = retryRead(() -> runtime.call("POST", "/api/v1/runtime-view/tasks:preview", 100, true));
        Map<String, String> bands = new HashMap<>();
        for (Object raw : array(result.get("entries"))) {
            var row = object(raw);
            require(bands.put(text(row.get("taskId")), text(row.get("scoreBand"))) == null, "duplicate-task-preview");
        }
        require(tasks.stream().allMatch(t -> bands.containsKey(t.id)), "task-preview-missing");
        return bands;
    }
    private boolean allTerminal() throws Exception { var bands = taskBands(); return tasks.stream().allMatch(t -> "terminal".equals(bands.get(t.id))); }
    private long backgroundSuccesses() { return tasks.stream().filter(t -> !t.witness).mapToLong(t -> t.succeeded.size()).sum(); }
    private List<Worker> targets() { return workers.stream().filter(Worker::target).toList(); }
    private void phase(String value) { stage = value; System.out.println("DYNAMIC_MATCHING stage=" + value); }
    private void launch(Action action) {
        readers.submit(() -> {
            while (!stopping.get()) {
                try { action.run(); Thread.sleep(200); }
                catch (TemporaryRead ignored) {
                    temporaryReads.incrementAndGet();
                    try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
                catch (Throwable error) { if (!stopping.get()) failure.compareAndSet(null, error); return; }
            }
        });
    }
    private void healthy() {
        Throwable error = failure.get();
        if (error != null) throw new IllegalStateException("observer-failed", error);
        require(System.nanoTime() < Math.min(workloadDeadline, dynamicDeadline), "phase-deadline");
    }
    private void until(long deadline, Condition condition, String code) throws Exception {
        while (System.nanoTime() < deadline) {
            healthy();
            try {
                if (condition.test()) {
                    healthy();
                    require(System.nanoTime() < deadline, code + "-deadline");
                    return;
                }
            } catch (TemporaryRead ignored) { temporaryReads.incrementAndGet(); }
            Thread.sleep(50);
        }
        throw new ProofFailure(code + "-deadline");
    }
    private Map<String, Object> retryRead(Read read) throws Exception {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        until(Math.min(Math.min(workloadDeadline, dynamicDeadline), System.nanoTime() + TimeUnit.SECONDS.toNanos(5)),
                () -> { result.set(read.get()); return true; }, "observation");
        return result.get();
    }
    private void save(boolean success) throws Exception {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("schemaVersion", 1); evidence.put("phase", stage);
        evidence.put("status", success ? "pending-runner-audit" : "failed");
        evidence.put("harnessStatus", success ? "succeeded" : "failed");
        if (!success && failure.get() != null) {
            String code = failure.get().getClass().getSimpleName();
            for (Throwable error = failure.get(); error != null; error = error.getCause())
                if (error instanceof ProofFailure rejected) code = rejected.code;
            evidence.put("failureCode", code);
        }
        evidence.put("workers", workers.stream().map(w -> Map.of("workerGroupId", w.group, "labWorkerKey", w.key,
                "workerId", w.id == null ? "unobserved" : w.id, "baselineSha256", digest(new TreeMap<>(w.baseline)))).toList());
        evidence.put("mutationRequests", mutations.get()); evidence.put("sendAcceptedCount", accepted.get());
        evidence.put("platformMutationRequests", platformMutations.get());
        evidence.put("adapterReads", adapterReads.get()); evidence.put("serverReads", serverReads.get());
        evidence.put("temporaryReadFailures", temporaryReads.get()); evidence.put("journalRecords", cursor);
        evidence.put("elapsedMillis", workloadStarted == 0 ? 0 : millisSince(workloadStarted));
        evidence.put("dynamicElapsedMillis", dynamicElapsedMillis);
        evidence.put("checkpoints", checkpoints);
        evidence.put("tasks", tasks.stream().map(t -> Map.of("taskId", t.id, "label", t.label,
                "submitted", t.tokens.size(), "succeeded", t.succeeded.size(), "enteredAttempts", t.entries.get(),
                "completedTokens", t.completed.size(), "executorsSha256", digest(t.executors.stream().sorted().toList()))).toList());
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, Jsons.toJson(evidence) + "\n");
    }
    static String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Jsons.toJson(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("digest-failed", error); }
    }
    private static long millisSince(long since) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Condition { boolean test() throws Exception; }
    @FunctionalInterface private interface Read { Map<String, Object> get() throws Exception; }

    private static final class Worker {
        final String group, key;
        final Map<String, String> baseline;
        final List<Map<String, String>> history = new CopyOnWriteArrayList<>(), platformHistory = new CopyOnWriteArrayList<>();
        volatile String id;
        volatile Map<String, String> expected, platform = Map.of(), adapter, server, serverPlatform;
        volatile long lastMutation;
        Worker(String group, String key, Map<String, String> baseline) {
            this.group = group; this.key = key; this.baseline = baseline; expected = baseline;
            history.add(baseline); platformHistory.add(Map.of());
        }
        String coordinate() { return group + "/" + key; }
        String path() { return "/lab/v1/workers/" + coordinate(); }
        boolean target() { return group.equals(STRING) && "yes".equals(baseline.get("proofTarget")); }
    }
    private static final class Task {
        final String label, id, group;
        final boolean witness;
        final Set<String> allowed;
        final AtomicBoolean mayExecute;
        volatile boolean approved;
        volatile long eligibleSince;
        final List<String> tokens = new ArrayList<>();
        final Set<String> succeeded = ConcurrentHashMap.newKeySet(), completed = ConcurrentHashMap.newKeySet(), executors = ConcurrentHashMap.newKeySet();
        final AtomicLong entries = new AtomicLong(), lastEntry = new AtomicLong();
        Task(String label, String id, String group, boolean witness, Set<String> allowed, boolean admitted) {
            this.label = label; this.id = id; this.group = group; this.witness = witness;
            this.allowed = allowed; mayExecute = new AtomicBoolean(admitted);
        }
        boolean complete() { return succeeded.size() == tokens.size(); }
        void admit() { eligibleSince = System.nanoTime(); mayExecute.set(true); }
    }
}
