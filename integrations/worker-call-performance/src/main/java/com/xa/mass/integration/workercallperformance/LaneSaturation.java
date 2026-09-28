package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saturation-mode performance-lane case: each Group receives a fresh finite Task with a deep
 * pre-seeded backlog; approval opens a fixed window with no HTTP load, and closing the Tasks ends
 * it. Completed Items are counted from the success-only export of the closed Tasks, bounded by
 * the leases still held when the window closed. With a near-zero Handler every Worker is expected
 * to hold a lease at saturation, so the primary result is the per-Worker turnaround: the platform
 * time of one lease cycle (acquire, deliver, execute, Result, release, due again).
 */
final class LaneSaturation {
    enum Path { TASK_ANY, TASK_TARGETED;
        String caseName() { return "sat-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    }
    static final int ITEMS_PER_GROUP = 150_000;
    static final int APPEND_BATCH = 100;
    static final int APPEND_CONCURRENCY = 16;
    static final int WINDOW_SECONDS = 30;
    static final long SETTLE_TIMEOUT_NANOS = 30_000_000_000L;

    private LaneSaturation() {}

    static boolean handles(String name) {
        return name != null && name.startsWith("sat-");
    }

    static Path parse(String name) {
        for (Path path : Path.values()) if (path.caseName().equals(name)) return path;
        throw new IllegalArgumentException("Unknown saturation case");
    }

    static void run(Map<String, String> options) throws Exception {
        var path = parse(options.get("--case"));
        java.nio.file.Path output = java.nio.file.Path.of(options.get("--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("phase", "case");
        summary.put("mode", "saturation");
        summary.put("case", path.caseName());
        summary.put("path", path.caseName().substring(4));
        summary.put("repetition", Integer.parseInt(options.getOrDefault("--repetition", "1")));
        summary.put("itemsPerGroup", ITEMS_PER_GROUP);
        summary.put("measurementSeconds", WINDOW_SECONDS);
        summary.put("status", "failed");
        var workerSamples = new ArrayList<Map<String, Object>>();
        var invalid = new ArrayList<String>();
        Exception failure = null;
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"))) {
            var world = LaneWorld.readWorld(java.nio.file.Path.of(options.get("--world")));
            String prefix = UUID.randomUUID().toString();
            summary.put("stage", "seed");
            long seedStarted = System.nanoTime();
            var tasks = new LinkedHashMap<String, String>();
            for (String group : LaneWorld.GROUPS) tasks.put(group, createTask(api, group, path));
            seed(api, path, world, tasks, prefix);
            summary.put("seedMillis", (System.nanoTime() - seedStarted) / 1_000_000);

            summary.put("stage", "window");
            var sampling = new AtomicBoolean(true);
            long started = System.currentTimeMillis();
            summary.put("measurementStartedEpochMillis", started);
            for (String task : tasks.values()) approve(api, task);
            Thread sampler = Thread.ofVirtual().name("lane-saturation-sampler").start(() -> {
                while (sampling.get()) {
                    try {
                        workerSamples.add(sampleWorkers(api, world, System.currentTimeMillis() - started));
                        Thread.sleep(LaneCase.SAMPLE_INTERVAL_MILLIS);
                    } catch (InterruptedException stopped) {
                        return;
                    } catch (Exception unavailable) {
                        workerSamples.add(Map.of("offsetMillis", System.currentTimeMillis() - started, "unavailable", true));
                    }
                }
            });
            Thread.sleep(started + WINDOW_SECONDS * 1_000L - System.currentTimeMillis());
            sampling.set(false);
            sampler.interrupt();
            sampler.join(10_000);
            // Leases held at the window end may still complete after close: the count's error bound.
            var atEnd = sampleWorkers(api, world, System.currentTimeMillis() - started);
            for (String task : tasks.values()) close(api, task);
            long ended = System.currentTimeMillis();
            summary.put("measurementEndedEpochMillis", ended);
            long heldAtEnd = LaneWorld.GROUPS.stream().mapToLong(g -> heldCount(atEnd, g)).sum();
            summary.put("heldLeasesAtWindowEnd", heldAtEnd);

            summary.put("stage", "settle");
            settle(api, world);
            var completed = new LinkedHashMap<String, Long>();
            for (var task : tasks.entrySet()) completed.put(task.getKey(), exportedSuccesses(api, task.getValue()));
            summary.put("completedByGroup", completed);
            double seconds = (ended - started) / 1000.0;
            long total = completed.values().stream().mapToLong(Long::longValue).sum();
            summary.put("completedPerSecond", total / seconds);
            summary.put("completedPerSecondErrorBound", heldAtEnd / seconds);
            summary.put("perWorkerTurnaroundMillis", perWorkerTurnaroundMillis(total, seconds));
            summary.put("workersBound", !LaneCase.workersAvailable(workerSamples));
            if (completed.values().stream().anyMatch(count -> count >= ITEMS_PER_GROUP)) invalid.add("items-exhausted");
            summary.put("status", invalid.isEmpty() ? "passed" : "invalid");
            summary.put("stage", "complete");
        } catch (Exception error) {
            failure = error;
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
        } finally {
            summary.put("invalidReasons", invalid);
            summary.put("workerSamples", workerSamples);
            summary.put("leaseHeldPeakRatio", LaneCase.leaseHeldPeakRatio(workerSamples));
            Files.writeString(output.resolve("case.json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Lane saturation case failed; inspect safe summary", failure);
    }

    /** Average platform time of one Worker lease cycle while every Worker stays busy. */
    static Double perWorkerTurnaroundMillis(long completed, double seconds) {
        int workers = LaneWorld.GROUPS.size() * LaneWorld.WORKERS_PER_GROUP;
        return completed == 0 ? null : workers * seconds * 1000 / completed;
    }

    private static String createTask(CallApi api, String group, Path path) throws Exception {
        var refill = path == Path.TASK_ANY
                ? List.of(Map.of("poolName", "any", "target", Map.of(), "count", LaneCase.ASSIGNMENT_BATCH_LIMIT))
                : List.of();
        return CallApi.string(api.post("/api/v1/tasks", Map.of("projectId", LaneWorld.PROJECT, "workerGroupId", group,
                "refill", refill, "priority", 50, "maxRetryTimes", 3)), "taskId");
    }

    /** Appends every Item before approval, so the window starts with the complete backlog. */
    private static void seed(CallApi api, Path path, Map<String, List<String>> world, Map<String, String> tasks,
                             String prefix) throws Exception {
        var permits = new Semaphore(APPEND_CONCURRENCY);
        var failures = ConcurrentHashMap.<String>newKeySet();
        try (var execution = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String group : LaneWorld.GROUPS) {
                var ids = world.get(group);
                for (int offset = 0; offset < ITEMS_PER_GROUP; offset += APPEND_BATCH) {
                    int first = offset;
                    permits.acquire();
                    execution.execute(() -> {
                        try {
                            appendBatch(api, tasks.get(group), items(path, group, ids, prefix, first));
                        } catch (Exception error) {
                            failures.add(error.getClass().getSimpleName());
                        } finally {
                            permits.release();
                        }
                    });
                }
            }
        }
        if (!failures.isEmpty()) throw new IllegalStateException("Saturation seeding failed: " + failures);
    }

    static List<Map<String, Object>> items(Path path, String group, List<String> ids, String prefix, int first) {
        var items = new ArrayList<Map<String, Object>>(APPEND_BATCH);
        for (int index = first; index < first + APPEND_BATCH; index++) {
            Map<String, Object> selector = path == Path.TASK_ANY
                    ? Map.of("executorName", "worker.any", "input", Map.of())
                    : Map.of("executorName", "workerId", "input", ids.get(index % ids.size()));
            items.add(Map.of("messageId", prefix + "-" + group + "-" + index, "eventCode", "extension.worker.string.md5",
                    "payload", Map.of("value", CallApi.INPUT), "workerSelector", selector, "ttlMillis", 900_000));
        }
        return items;
    }

    private static void appendBatch(CallApi api, String task, List<Map<String, Object>> items) throws Exception {
        var appended = api.post("/api/v1/tasks/" + task + "/items", items);
        if (!appended.keySet().equals(items.stream().map(i -> i.get("messageId")).collect(java.util.stream.Collectors.toSet()))
                || appended.values().stream().anyMatch(v -> !"applied".equals(CallApi.object(v).get("status"))))
            throw new CallLoad.ProtocolFailure("Saturation append did not apply the exact Item set");
    }

    private static void approve(CallApi api, String task) throws Exception {
        var approval = api.post("/api/v1/tasks/" + task + "/approve", Map.of());
        if (!Set.of("applied", "unchanged").contains(approval.get("status"))) throw new CallLoad.ProtocolFailure("Task approval failed");
    }

    private static void close(CallApi api, String task) throws Exception {
        var closed = api.post("/api/v1/tasks/" + task + "/close", Map.of());
        if (!Set.of("applied", "unchanged").contains(closed.get("status"))) throw new CallLoad.ProtocolFailure("Task close failed");
    }

    /** Waits until no lease remains held, so every in-flight Item has closed before export. */
    private static void settle(CallApi api, Map<String, List<String>> world) throws Exception {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS;
        do {
            var sample = sampleWorkers(api, world, 0);
            if (LaneWorld.GROUPS.stream().allMatch(g -> heldCount(sample, g) == 0)) return;
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Held leases did not settle after closing the saturation Tasks");
    }

    private static long exportedSuccesses(CallApi api, String task) throws Exception {
        return api.exportCount(task);
    }

    private static Map<String, Object> sampleWorkers(CallApi api, Map<String, List<String>> world, long offset) throws Exception {
        var sample = new LinkedHashMap<String, Object>();
        sample.put("offsetMillis", offset);
        for (String group : LaneWorld.GROUPS) {
            var counts = new LinkedHashMap<String, Long>();
            LaneWorld.observeScheduling(api, group, world.get(group))
                    .values().forEach(state -> counts.merge(String.valueOf(state), 1L, Long::sum));
            sample.put(group, counts);
        }
        return sample;
    }

    private static long heldCount(Map<String, Object> sample, String group) {
        return ((Number) CallApi.object(sample.get(group)).getOrDefault("held-hot", 0L)).longValue();
    }
}
