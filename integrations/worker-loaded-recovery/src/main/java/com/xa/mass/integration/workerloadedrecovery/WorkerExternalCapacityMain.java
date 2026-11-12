package com.xa.mass.integration.workerloadedrecovery;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Artifact-consumer capacity comparison. Owns no Server, Redis or remote process lifecycle. */
public final class WorkerExternalCapacityMain {
    static final int TASK_COUNT = 10;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_IN_FLIGHT = 8;
    private static final long START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private final WorkerExternalCapacityOptions options;
    private final LoadedRecoveryApiClient api;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_IN_FLIGHT);
    private final List<String> knownTaskIds = new ArrayList<>();
    private final List<Map<String, Object>> unknownWrites = new ArrayList<>();
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private final List<Map<String, Object>> cleanup = new ArrayList<>();
    private final Map<String, String> baseline = new LinkedHashMap<>();
    private final Map<String, Long> phaseDurationsMillis = new LinkedHashMap<>();
    private final String runId = UUID.randomUUID().toString();
    private String phase = "preflight";
    private long phaseStarted = System.nanoTime();

    WorkerExternalCapacityMain(WorkerExternalCapacityOptions options, LoadedRecoveryApiClient api) {
        this.options = options;
        this.api = api;
    }

    public static void main(String[] arguments) {
        WorkerExternalCapacityOptions options;
        LoadedRecoveryApiClient api;
        try {
            options = WorkerExternalCapacityOptions.parse(arguments);
            String accessKey = options.readAccessKey();
            prepareOutput(options.outputDirectory());
            api = new LoadedRecoveryApiClient(
                    new LoadedRecoveryHttpClient(options.labBaseUri(), options.requestTimeout(), Map.of()),
                    new LoadedRecoveryHttpClient(options.serverBaseUri(), options.requestTimeout(),
                            Map.of("Authorization", "Bearer " + accessKey)));
        } catch (Exception error) {
            System.err.println("External capacity configuration is invalid; no workload was started.");
            System.exit(2);
            return;
        }
        Map<String, Object> summary = new WorkerExternalCapacityMain(options, api).run();
        System.out.println(Jsons.toJson(summary));
        System.exit(exitCode((String) summary.get("status")));
    }

    static int exitCode(String status) {
        return switch (status) { case "passed" -> 0; case "unconfirmed" -> 3; default -> 1; };
    }

    static void prepareOutput(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            if (files.findAny().isPresent()) throw new IllegalArgumentException("output-dir must be empty");
        }
        Files.createDirectories(directory.resolve("private"));
    }

    Map<String, Object> run() {
        String outcome = "passed";
        String failureKind = "";
        try {
            preflight();
            phase("inventory");
            List<String> keys = inventory();
            phase("ramp");
            ramp(keys);
            establishBaseline(keys);
            phase("initial-stability");
            Map<String, Object> initialGate = awaitStable(keys, List.of(), options.minimumInitialConnected());
            phase("case-initial");
            cases.add(runCase("initial", keys, List.of(), options.minimumInitialConnected(), initialGate));
            phase("contraction");
            List<String> retained = keys.subList(0, options.retainedWorkers());
            List<String> stopped = keys.subList(options.retainedWorkers(), keys.size());
            for (List<String> page : pages(stopped)) {
                write("stop-workers", Map.of("count", page.size(), "coordinateDigest", LoadedRecoveryEvidence.identityDigest(page)), () -> {
                    api.stopWorkers(options.workerGroupId(), page);
                    return Map.of("acceptedCount", page.size());
                });
            }
            phase("retained-stability");
            Map<String, Object> retainedGate = awaitStable(retained, stopped, options.minimumRetainedConnected());
            phase("case-retained");
            cases.add(runCase("retained", retained, stopped, options.minimumRetainedConnected(), retainedGate));
            phase("complete");
        } catch (RuntimeException error) {
            outcome = unconfirmed(error) || !unknownWrites.isEmpty() ? "unconfirmed" : "failed";
            failureKind = safeFailure(error);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(options.requestTimeout().toMillis() + 1_000, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                    outcome = "unconfirmed";
                    failureKind = "in-flight-requests-not-settled";
                }
            } catch (InterruptedException error) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                outcome = "unconfirmed";
                failureKind = "interrupted";
            }
            if (!"passed".equals(outcome)) cleanupKnownTasks();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("status", outcome);
        summary.put("outcome", outcome);
        summary.put("runId", runId);
        summary.put("phase", phase);
        phaseDurationsMillis.merge(phase, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStarted), Long::sum);
        summary.put("phaseDurationsMillis", Map.copyOf(phaseDurationsMillis));
        summary.put("failureKind", failureKind);
        summary.put("preparedWorkers", options.preparedWorkers());
        summary.put("retainedWorkers", options.retainedWorkers());
        summary.put("workloadItemsPerTask", options.workloadItemsPerTask());
        summary.put("expectedItems", 2 * TASK_COUNT * options.workloadItemsPerTask());
        summary.put("identityCount", baseline.size());
        summary.put("identityDigest", LoadedRecoveryEvidence.identityDigest(baseline.values()));
        summary.put("cases", cases);
        summary.put("knownTaskIds", List.copyOf(knownTaskIds));
        summary.put("unknownWrites", List.copyOf(unknownWrites));
        summary.put("cleanup", cleanup);
        summary.put("measurement", "finite-backlog WAN end-to-end; includes approval and terminal observation; excludes preload and verification");
        summary.put("nonClaims", List.of("Server capacity ceiling", "per-Worker execution coverage", "TPS/P99", "remote process resources", "Server recovery", "machine-proven Group exclusivity"));
        try {
            LoadedRecoveryEvidence.writeSummary(options.outputDirectory().resolve("summary.json"), summary);
        } catch (RuntimeException error) {
            summary.put("status", "unconfirmed");
            summary.put("outcome", "unconfirmed");
            summary.put("failureKind", "summary-write-failed");
        }
        return summary;
    }

    private void preflight() {
        Map<String, Object> bindings = api.groupBindings(options.workerGroupId());
        require(options.workerGroupId().equals(bindings.get("workerGroupId")), "preflight-group-identity");
        Map<String, Object> group = LoadedRecoveryJson.object(bindings.get("workerGroup"), "Group descriptor");
        require(options.workerGroupId().equals(group.get("workerGroupId")), "preflight-group-not-registered");
        require(LoadedRecoveryJson.array(group.get("eventCodes"), "eventCodes").contains("extension.worker.string.md5"), "preflight-md5-event-missing");
        Map<String, Object> matching = LoadedRecoveryJson.object(bindings.get("matching"), "Matching capabilities");
        require(LoadedRecoveryJson.array(matching.get("pools"), "pools").contains("any"), "preflight-any-pool-missing");
        boolean function = LoadedRecoveryJson.array(matching.get("functions"), "functions").stream()
                .map(value -> LoadedRecoveryJson.object(value, "function"))
                .anyMatch(value -> "worker.any".equals(value.get("name")) && "any".equals(value.get("poolName")));
        require(function, "preflight-worker-any-function-missing");
        boolean project = LoadedRecoveryJson.array(bindings.get("projects"), "projects").stream()
                .map(value -> LoadedRecoveryJson.object(value, "Project"))
                .anyMatch(value -> options.projectId().equals(value.get("projectId")));
        require(project, "preflight-project-group-binding-missing");
        event("preflight-complete", Map.of("groupRegistered", true, "md5Event", true, "anyPool", true,
                "workerAnyFunction", true, "projectBound", true, "exclusiveGroup", "operator-precondition-not-machine-proven"));
    }

    private List<String> inventory() {
        List<LoadedRecoveryApiClient.LabWorker> workers = api.labWorkers();
        require(workers.size() == options.preparedWorkers(), "inventory-count");
        Set<String> keys = new LinkedHashSet<>();
        for (var worker : workers) {
            require(options.workerGroupId().equals(worker.workerGroupId()), "inventory-group");
            require("STOPPED".equals(worker.desiredState()) && "STOPPED".equals(worker.runtimeState()), "inventory-must-start-stopped");
            require(keys.add(worker.labWorkerKey()), "duplicate-lab-coordinate");
        }
        return keys.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private void ramp(List<String> keys) {
        // At most eight submitted calls; each physical point start is at least 20 ms apart.
        List<Future<Map<String, Object>>> pending = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        RuntimeException failure = null;
        long next = System.nanoTime();
        for (String key : keys) {
            if (failed.get()) break;
            if (pending.size() == MAX_IN_FLIGHT) {
                try { await(pending.removeFirst()); } catch (RuntimeException error) { failure = error; failed.set(true); break; }
            }
            sleepUntil(next);
            next = System.nanoTime() + START_INTERVAL_NANOS;
            pending.add(executor.submit(() -> {
                if (failed.get()) return Map.of();
                try {
                    return write("start-worker", Map.of("coordinateDigest", LoadedRecoveryEvidence.identityDigest(List.of(key))), () -> {
                        api.startWorker(options.workerGroupId(), key);
                        return Map.of("acceptedCount", 1);
                    });
                } catch (RuntimeException error) { failed.set(true); throw error; }
            }));
        }
        for (Future<?> future : pending) {
            try { await(future); } catch (RuntimeException error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw failure;
        require(!failed.get(), "ramp-incomplete");
    }

    private void establishBaseline(List<String> keys) {
        long deadline = System.nanoTime() + options.maximumConvergenceWait().toNanos();
        Set<String> expected = Set.copyOf(keys);
        while (true) {
            Map<String, String> observed = new LinkedHashMap<>();
            Set<String> observedKeys = new LinkedHashSet<>();
            for (var worker : api.labWorkers()) {
                require(options.workerGroupId().equals(worker.workerGroupId()) && observedKeys.add(worker.labWorkerKey()), "inventory-changed");
                require("RUNNING".equals(worker.desiredState()), "worker-start-not-retained");
                if (worker.workerId() != null && !worker.workerId().isBlank()) observed.put(worker.labWorkerKey(), worker.workerId());
            }
            require(expected.equals(observedKeys), "inventory-changed");
            require(System.nanoTime() < deadline, "identity-convergence-timeout");
            if (observed.size() == keys.size()) {
                require(new LinkedHashSet<>(observed.values()).size() == keys.size(), "duplicate-worker-identity");
                for (String key : keys) baseline.put(key, observed.get(key));
                LoadedRecoveryEvidence.writeBaseline(options.outputDirectory().resolve("private/worker-identities.json"),
                        options.workerGroupId(), baseline);
                return;
            }
            require(System.nanoTime() < deadline, "identity-convergence-timeout");
            sleepMillis(Math.min(1_000, options.scanInterval().toMillis()));
        }
    }

    private Map<String, Object> awaitStable(List<String> active, List<String> stopped, int minimum) {
        long deadline = System.nanoTime() + options.maximumConvergenceWait().toNanos();
        long stableSince = 0;
        int consecutive = 0;
        int minimumObserved = active.size();
        while (true) {
            verifyLabIdentity(active, stopped);
            Scan scan = scan(active, stopped);
            long now = System.nanoTime();
            require(now < deadline, "connection-stability-timeout");
            if (scan.qualifies(minimum)) {
                if (stableSince == 0) stableSince = now;
                consecutive++;
                minimumObserved = Math.min(minimumObserved, scan.connected());
                if (consecutive >= 3 && now - stableSince >= options.stableHold().toNanos()) {
                    Map<String, Object> gate = new LinkedHashMap<>(scan.evidence());
                    gate.put("qualifyingScans", consecutive);
                    gate.put("stableMillis", TimeUnit.NANOSECONDS.toMillis(now - stableSince));
                    gate.put("minimumConnected", minimumObserved);
                    gate.put("requiredConnected", minimum);
                    return gate;
                }
            } else {
                stableSince = 0;
                consecutive = 0;
                minimumObserved = active.size();
            }
            require(now < deadline, "connection-stability-timeout");
            sleepMillis(options.scanInterval().toMillis());
        }
    }

    private void verifyLabIdentity(List<String> active, List<String> stopped) {
        Set<String> activeSet = Set.copyOf(active);
        Set<String> stoppedSet = Set.copyOf(stopped);
        Set<String> observed = new LinkedHashSet<>();
        for (var worker : api.labWorkers()) {
            require(options.workerGroupId().equals(worker.workerGroupId()) && observed.add(worker.labWorkerKey()), "inventory-changed");
            if (activeSet.contains(worker.labWorkerKey())) {
                require("RUNNING".equals(worker.desiredState()) && baseline.get(worker.labWorkerKey()).equals(worker.workerId()), "active-identity-changed");
            } else {
                require(stoppedSet.contains(worker.labWorkerKey()) && "STOPPED".equals(worker.desiredState()), "stopped-coordinate-changed");
            }
        }
        require(observed.equals(baseline.keySet()), "inventory-changed");
    }

    private Scan scan(List<String> active, List<String> stopped) {
        Set<String> activeIds = new LinkedHashSet<>(active.stream().map(baseline::get).toList());
        List<String> allIds = new ArrayList<>(activeIds);
        allIds.addAll(stopped.stream().map(baseline::get).toList());
        List<Callable<Scan>> requests = new ArrayList<>();
        for (List<String> page : pages(allIds)) requests.add(() -> {
            var network = api.observeNetwork(options.endpointManagerId(), page);
            var scheduling = api.observeScheduling(options.workerGroupId(), page);
            int connected = 0, stoppedConnected = 0, missing = 0;
            Map<String, Integer> states = new LinkedHashMap<>();
            for (String id : page) {
                require(Set.of("connected", "disconnected", "unknown").contains(network.get(id)), "unknown-network-state");
                require(Set.of("held-hot", "hot-score-overdue", "paused", "recovery", "cold", "missing").contains(scheduling.get(id)), "unknown-scheduling-state");
                if ("missing".equals(scheduling.get(id))) missing++;
                if ("connected".equals(network.get(id))) {
                    if (activeIds.contains(id)) connected++; else stoppedConnected++;
                }
                states.merge((activeIds.contains(id) ? "active:" : "stopped:")
                        + scheduling.get(id) + "|" + network.get(id), 1, Integer::sum);
            }
            return new Scan(connected, stoppedConnected, missing, states);
        });
        int connected = 0, stoppedConnected = 0, missing = 0;
        Map<String, Integer> states = new LinkedHashMap<>();
        for (Scan page : parallel(requests)) {
            connected += page.connected(); stoppedConnected += page.stoppedConnected(); missing += page.missing();
            page.states().forEach((key, count) -> states.merge(key, count, Integer::sum));
        }
        Scan result = new Scan(connected, stoppedConnected, missing, states);
        event("fleet-scan", result.evidence());
        return result;
    }

    private Map<String, Object> runCase(String label, List<String> active, List<String> stopped,
            int minimum, Map<String, Object> gate) {
        long preloadStarted = System.nanoTime();
        List<PreparedTask> tasks = new ArrayList<>();
        for (int index = 0; index < TASK_COUNT; index++) {
            String requestId = UUID.randomUUID().toString();
            Map<String, Object> created = write("create-task", Map.of("requestId", requestId, "case", label), () -> {
                String id = api.createTask(options.projectId(), options.workerGroupId(), requestId);
                require(!knownTaskIds.contains(id), "duplicate-created-task-identity");
                knownTaskIds.add(id);
                return Map.of("taskId", id);
            });
            tasks.add(prepareTask((String) created.get("taskId"), runId + ":" + label + ":" + index, options.workloadItemsPerTask()));
        }
        AtomicBoolean abort = new AtomicBoolean();
        List<Callable<Boolean>> preload = new ArrayList<>();
        for (PreparedTask task : tasks) preload.add(() -> {
            for (List<LoadedRecoveryApiClient.TaskItem> page : pages(task.items())) {
                if (abort.get()) return false;
                try {
                    write("append-items", Map.of("taskId", task.id(), "count", page.size(),
                            "messageIdDigest", LoadedRecoveryEvidence.identityDigest(page.stream().map(LoadedRecoveryApiClient.TaskItem::messageId).toList())), () -> {
                        api.appendItems(task.id(), page);
                        return Map.of("appliedCount", page.size());
                    });
                } catch (RuntimeException error) { abort.set(true); throw error; }
            }
            return true;
        });
        require(parallel(preload).stream().allMatch(Boolean::booleanValue), "preload-incomplete");
        var seeded = api.taskStats(options.projectId(), tasks.stream().map(PreparedTask::id).toList());
        for (PreparedTask task : tasks) require(counts(seeded, task.id()).total() == task.items().size(), "seed-count-mismatch");
        long preloadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preloadStarted);
        verifyLabIdentity(active, stopped);
        Scan before = scan(active, stopped);
        require(before.qualifies(minimum), "pre-approval-fleet-threshold");
        long started = System.nanoTime();
        String startedAt = Instant.now().toString();
        event("measurement-start", Map.of("case", label, "taskCount", TASK_COUNT));
        for (PreparedTask task : tasks) write("approve-task", Map.of("taskId", task.id()), () -> {
            api.approveTask(task.id(), task.items().size());
            return Map.of("approved", true);
        });
        Set<String> terminal = new LinkedHashSet<>();
        Map<String, Long> terminalObservedMillis = new LinkedHashMap<>();
        long lastProgress = started, lastScan = started;
        long succeeded = 0;
        int minimumObserved = before.connected();
        long finished;
        while (true) {
            long cycle = System.nanoTime();
            var statistics = api.taskStats(options.projectId(), tasks.stream().map(PreparedTask::id).toList());
            long observedSucceeded = 0;
            Map<String, Object> progress = new LinkedHashMap<>();
            for (PreparedTask task : tasks) {
                Counts counts = counts(statistics, task.id());
                require(counts.total() == task.items().size(), "workload-count-changed");
                require(counts.failed() == 0, "failed-items-observed");
                observedSucceeded += counts.succeeded();
                progress.put(task.id(), Map.of("total", counts.total(), "succeeded", counts.succeeded(), "failed", counts.failed()));
            }
            long observedAt = System.nanoTime();
            require(!LoadedRecoveryWorkload.progressDeadlineExceeded((int) succeeded,
                    observedAt - started, observedAt - lastProgress), "workload-progress-timeout");
            if (observedSucceeded > succeeded) lastProgress = observedAt;
            require(observedSucceeded >= succeeded, "success-count-regressed");
            succeeded = observedSucceeded;
            List<Callable<Map.Entry<String, Long>>> terminalReads = new ArrayList<>();
            for (PreparedTask task : tasks) if (!terminal.contains(task.id())) terminalReads.add(() -> {
                var point = api.task(options.projectId(), task.id());
                return Map.entry("terminal".equals(point.get("scoreBand")) ? task.id() : "",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            });
            for (var observation : parallel(terminalReads)) if (!observation.getKey().isEmpty()) {
                terminal.add(observation.getKey());
                terminalObservedMillis.put(observation.getKey(), observation.getValue());
            }
            event("task-progress", Map.of("case", label, "tasks", progress, "terminalTaskCount", terminal.size()));
            finished = System.nanoTime();
            require(finished - started <= options.taskResultWait().toNanos(), "task-terminal-timeout");
            if (terminal.size() == TASK_COUNT) break;
            require(!LoadedRecoveryWorkload.progressDeadlineExceeded((int) succeeded,
                    finished - started, finished - lastProgress), "workload-progress-timeout");
            if (finished - lastScan >= options.scanInterval().toNanos()) {
                Scan current = scan(active, stopped);
                minimumObserved = Math.min(minimumObserved, current.connected());
                require(current.qualifies(minimum), "in-work-fleet-threshold");
                lastScan = System.nanoTime();
            }
            sleepUntil(cycle + TimeUnit.SECONDS.toNanos(1));
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(finished - started);
        event("measurement-finish", Map.of("case", label, "elapsedMillis", elapsedMillis));
        // State and complete export checks intentionally run outside the measurement interval.
        long verificationStarted = System.nanoTime();
        long exportNanos = 0;
        int verified = 0;
        List<Map<String, Object>> perTask = new ArrayList<>();
        for (PreparedTask task : tasks) {
            for (List<String> page : pages(new ArrayList<>(task.inputs().keySet()))) {
                Map<String, Object> states = api.itemStates(task.id(), page);
                for (String id : page) {
                    Map<String, Object> state = LoadedRecoveryJson.object(states.get(id), "Item state");
                    require("terminal".equals(state.get("band")) && LoadedRecoveryJson.integer(state, "tag") == 6, "item-not-terminal-success");
                }
            }
            long exportStarted = System.nanoTime();
            var exported = api.exportTask(task.id(), (id, payload) -> verifyMd5(task.inputs(), id, payload));
            long taskExportNanos = System.nanoTime() - exportStarted;
            exportNanos += taskExportNanos;
            require(exported.ready() && exported.messageIds().equals(task.inputs().keySet()), "export-identities-mismatch");
            verified += exported.messageIds().size();
            perTask.add(new LinkedHashMap<>(Map.of("taskId", task.id(), "expectedItems", task.items().size(),
                    "verifiedItems", exported.messageIds().size(), "terminalObservedMillis", terminalObservedMillis.get(task.id()),
                    "exportMillis", TimeUnit.NANOSECONDS.toMillis(taskExportNanos))));
        }
        var finalStats = api.taskStats(options.projectId(), tasks.stream().map(PreparedTask::id).toList());
        for (int index = 0; index < tasks.size(); index++) {
            PreparedTask task = tasks.get(index);
            Counts counts = counts(finalStats, task.id());
            require(counts.total() == task.items().size() && counts.succeeded() == task.items().size() && counts.failed() == 0, "final-statistics-mismatch");
            perTask.get(index).put("totalItems", counts.total());
            perTask.get(index).put("succeededItems", counts.succeeded());
            perTask.get(index).put("failedItems", counts.failed());
        }
        long verificationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - verificationStarted);
        Map<String, Object> post = awaitStable(active, stopped, minimum);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", label);
        result.put("preparedWorkers", options.preparedWorkers());
        result.put("activeWorkers", active.size());
        result.put("taskCount", TASK_COUNT);
        result.put("taskIds", tasks.stream().map(PreparedTask::id).toList());
        result.put("tasks", perTask);
        result.put("expectedItems", TASK_COUNT * options.workloadItemsPerTask());
        result.put("succeededItems", verified);
        result.put("verifiedItems", verified);
        result.put("terminalTaskCount", terminal.size());
        result.put("startedAt", startedAt);
        result.put("elapsedMillis", elapsedMillis);
        result.put("preloadMillis", preloadMillis);
        result.put("verificationMillis", verificationMillis);
        result.put("exportMillis", TimeUnit.NANOSECONDS.toMillis(exportNanos));
        result.put("observedItemsPerSecond", verified * 1_000.0 / Math.max(1, elapsedMillis));
        result.put("minimumConnected", minimumObserved);
        result.put("preWorkStability", gate);
        result.put("postWorkStability", post);
        result.put("status", "passed");
        event("case-complete", result);
        return result;
    }

    static PreparedTask prepareTask(String taskId, String prefix, int count) {
        List<LoadedRecoveryApiClient.TaskItem> items = new ArrayList<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String id = prefix + ":" + index;
            String input = List.of("ASCII", "中文", "café", "😀").get(index % 4) + ":external-capacity:" + id;
            inputs.put(id, input);
            items.add(new LoadedRecoveryApiClient.TaskItem(id, "extension.worker.string.md5", Map.of("value", input)));
        }
        return new PreparedTask(taskId, List.copyOf(items), java.util.Collections.unmodifiableMap(inputs));
    }

    static void verifyMd5(Map<String, String> inputs, String id, String payload) {
        String input = inputs.get(id);
        require(input != null, "unexpected-export-identity");
        Map<String, Object> value;
        try { value = Jsons.parseObject(payload); }
        catch (RuntimeException error) { throw new AssertionFailure("invalid-md5-result-json"); }
        require(value.keySet().equals(Set.of("input", "valid", "md5"))
                && Boolean.TRUE.equals(value.get("valid")) && input.equals(value.get("input"))
                && md5(input).equals(value.get("md5")), "md5-result-mismatch");
    }

    private static String md5(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("JVM MD5 unavailable"); }
    }

    static Counts counts(Map<String, Object> stats, String taskId) {
        Map<String, Object> value = LoadedRecoveryJson.object(stats.get(taskId), "Task statistics");
        long total = LoadedRecoveryJson.integer(value, "total");
        Map<String, Object> byTag = LoadedRecoveryJson.object(value.get("countsByTag"), "countsByTag");
        long sum = 0;
        for (String tag : byTag.keySet()) {
            require(tag.matches("[1-9]"), "unknown-item-tag");
            long count = LoadedRecoveryJson.integer(byTag, tag);
            require(count >= 0, "negative-item-count");
            sum = Math.addExact(sum, count);
        }
        require(total >= 0 && sum == total, "inconsistent-item-counts");
        return new Counts(total, byTag.containsKey("6") ? LoadedRecoveryJson.integer(byTag, "6") : 0,
                byTag.containsKey("5") ? LoadedRecoveryJson.integer(byTag, "5") : 0);
    }

    private void cleanupKnownTasks() {
        // No directory discovery: only IDs returned by this run's confirmed create requests.
        for (String id : List.copyOf(knownTaskIds)) {
            try {
                var task = api.task(options.projectId(), id);
                if ("terminal".equals(task.get("scoreBand"))) {
                    cleanup.add(Map.of("taskId", id, "status", "already-terminal"));
                } else {
                    write("cleanup-close-task", Map.of("taskId", id), () -> {
                        api.closeTask(id);
                        return Map.of("closed", true);
                    });
                    cleanup.add(Map.of("taskId", id, "status", "close-confirmed"));
                }
            } catch (RuntimeException error) {
                cleanup.add(Map.of("taskId", id, "status", "unconfirmed", "failureKind", safeFailure(error)));
            }
        }
    }

    private <T> List<T> parallel(List<Callable<T>> operations) {
        List<T> values = new ArrayList<>();
        RuntimeException failure = null;
        // Bounded submission, not merely a bounded number of executing threads.
        for (int offset = 0; offset < operations.size(); offset += MAX_IN_FLIGHT) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> operation : operations.subList(offset, Math.min(offset + MAX_IN_FLIGHT, operations.size()))) {
                futures.add(executor.submit(operation));
            }
            for (Future<T> future : futures) {
                try { values.add(await(future)); }
                catch (RuntimeException error) { if (failure == null) failure = error; }
            }
            if (failure != null) throw failure;
        }
        return values;
    }

    private Map<String, Object> write(String operation, Map<String, Object> context,
            Supplier<Map<String, Object>> request) {
        String receiptId = UUID.randomUUID().toString();
        receipt(operation, receiptId, "sending", context);
        Map<String, Object> response;
        try { response = request.get(); }
        catch (RuntimeException error) {
            boolean rejected = error instanceof LoadedRecoveryApiClient.HttpStatusFailure status
                    && status.status() >= 400 && status.status() < 500;
            Map<String, Object> entry = new LinkedHashMap<>(context);
            entry.put("operation", operation);
            entry.put("receiptId", receiptId);
            entry.put("failureKind", safeFailure(error));
            if (!rejected) synchronized (this) { unknownWrites.add(Map.copyOf(entry)); }
            receipt(operation, receiptId, rejected ? "rejected" : "unconfirmed", entry);
            throw error;
        }
        Map<String, Object> confirmed = new LinkedHashMap<>(context);
        confirmed.putAll(response);
        receipt(operation, receiptId, "confirmed", confirmed);
        return response;
    }

    private synchronized void receipt(String operation, String id, String status, Map<String, Object> context) {
        Map<String, Object> entry = new LinkedHashMap<>(context);
        entry.put("at", Instant.now().toString());
        entry.put("operation", operation);
        entry.put("receiptId", id);
        entry.put("status", status);
        LoadedRecoveryEvidence.appendTimeline(options.outputDirectory().resolve("receipts.jsonl"), entry);
    }

    private void phase(String value) {
        long now = System.nanoTime();
        phaseDurationsMillis.merge(phase, TimeUnit.NANOSECONDS.toMillis(now - phaseStarted), Long::sum);
        phaseStarted = now;
        phase = value;
        event("phase", Map.of());
    }

    private void event(String kind, Map<String, Object> fields) {
        Map<String, Object> entry = new LinkedHashMap<>(fields);
        entry.put("at", Instant.now().toString()); entry.put("phase", phase); entry.put("kind", kind);
        LoadedRecoveryEvidence.appendTimeline(options.outputDirectory().resolve("timeline.jsonl"), entry);
    }

    private static boolean unconfirmed(Throwable error) {
        return error instanceof LoadedRecoveryHttpClient.TransportFailure
                || error instanceof LoadedRecoveryApiClient.HttpStatusFailure status && status.status() >= 500
                || error instanceof IllegalStateException && error.getCause() instanceof IOException;
    }

    private static String safeFailure(Throwable error) {
        if (error instanceof AssertionFailure assertion) return assertion.getMessage();
        if (error instanceof LoadedRecoveryApiClient.HttpStatusFailure status) return "http-" + status.status();
        if (error instanceof LoadedRecoveryHttpClient.TransportFailure) return "http-response-unconfirmed";
        return "invalid-response-or-evidence";
    }

    private static <T> T await(Future<T> future) {
        try { return future.get(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new LoadedRecoveryHttpClient.TransportFailure(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("bounded operation failed");
        }
    }

    static <T> List<List<T>> pages(List<T> values) {
        List<List<T>> pages = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += PAGE_SIZE) {
            pages.add(List.copyOf(values.subList(offset, Math.min(offset + PAGE_SIZE, values.size()))));
        }
        return pages;
    }

    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionFailure(failure);
    }

    private static void sleepMillis(long millis) { sleepUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)); }

    private static void sleepUntil(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        try { TimeUnit.NANOSECONDS.sleep(remaining); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new LoadedRecoveryHttpClient.TransportFailure(error);
        }
    }

    record PreparedTask(String id, List<LoadedRecoveryApiClient.TaskItem> items, Map<String, String> inputs) { }
    record Counts(long total, long succeeded, long failed) { }
    record Scan(int connected, int stoppedConnected, int missing, Map<String, Integer> states) {
        boolean qualifies(int minimum) { return connected >= minimum && stoppedConnected == 0 && missing == 0; }
        Map<String, Object> evidence() {
            return Map.of("activeConnected", connected, "stoppedConnected", stoppedConnected,
                    "missingSchedulingIdentities", missing, "states", states);
        }
    }
    static final class AssertionFailure extends IllegalStateException {
        AssertionFailure(String kind) { super(kind); }
    }
}
