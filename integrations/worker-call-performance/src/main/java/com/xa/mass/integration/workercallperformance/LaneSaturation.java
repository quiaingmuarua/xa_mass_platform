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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Saturation-mode performance-lane case: each Group receives a fresh finite Task with a deep
 * pre-seeded backlog; approval opens a fixed window with no HTTP load, and closing the Tasks ends
 * it. Export verifies unique successful Items; the offline JFR reader counts success events
 * inside the measurement window. Closing and draining never extend that window.
 */
final class LaneSaturation {
    enum Path { TASK_ANY, TASK_TARGETED;
        String caseName() { return "sat-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    }
    static final int ITEMS_PER_GROUP = 150_000;
    static final int APPEND_BATCH = 100;

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
        var settings = ExperimentConfig.settings(options);
        java.nio.file.Path output = java.nio.file.Path.of(options.get("--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("phase", "case");
        summary.put("mode", "saturation");
        summary.put("case", path.caseName());
        summary.put("path", path.caseName().substring(4));
        summary.put("repetition", Integer.parseInt(options.getOrDefault("--repetition", "1")));
        summary.put("itemsPerGroup", settings.items());
        summary.put("measurementSeconds", settings.seconds());
        summary.put("warmupSeconds", settings.warmupSeconds());
        summary.put("workersPerGroup", settings.workers());
        summary.put("measurementVersion", 2);
        summary.put("status", "failed");
        var workerSamples = new ArrayList<Map<String, Object>>();
        var invalid = new ArrayList<String>();
        Exception failure = null;
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"))) {
            var world = LaneWorld.readWorld(java.nio.file.Path.of(options.get("--world")));
            if (world.values().stream().anyMatch(ids -> ids.size() != settings.workers()))
                throw new IllegalStateException("Experiment and bootstrap Worker counts disagree");
            String prefix = UUID.randomUUID().toString();
            summary.put("stage", "seed");
            long seedStarted = System.nanoTime();
            var tasks = new LinkedHashMap<String, String>();
            for (String group : LaneWorld.GROUPS) tasks.put(group, createTask(api, group, path));
            seed(api, path, world, tasks, prefix, settings);
            summary.put("seedMillis", (System.nanoTime() - seedStarted) / 1_000_000);

            summary.put("stage", "window");
            var sampling = new AtomicBoolean(true);
            summary.put("lifecycleStartedEpochMillis", System.currentTimeMillis());
            for (String task : tasks.values()) approve(api, task);
            Thread.sleep(settings.warmupSeconds() * 1000L);
            long started = System.currentTimeMillis();
            long startedNanos = System.nanoTime();
            long ended = started + settings.seconds() * 1000L;
            summary.put("measurementStartedEpochMillis", started);
            summary.put("measurementEndedEpochMillis", ended);
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
            long remaining = settings.seconds() * 1_000_000_000L - (System.nanoTime() - startedNanos);
            if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            long closeAt = System.currentTimeMillis();
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            if (Math.abs(closeAt - started - elapsedMillis) > 100) invalid.add("wall-clock-discontinuity");
            summary.put("closeDelayMillis", Math.max(0, closeAt - ended));
            for (String task : tasks.values()) close(api, task);
            sampling.set(false);
            sampler.interrupt();
            sampler.join(10_000);
            summary.put("stage", "settle");
            LaneWorld.quiesce(api, world, settings.settleSeconds());
            var completed = new LinkedHashMap<String, Long>();
            for (var task : tasks.entrySet())
                completed.put(task.getKey(), api.exportVerified(task.getValue(), prefix + "-" + task.getKey() + "-", settings.items()));
            summary.put("completedByGroup", completed);
            long total = completed.values().stream().mapToLong(Long::longValue).sum();
            summary.put("exportedSuccessCount", total);
            summary.put("validationEndedEpochMillis", System.currentTimeMillis());
            summary.put("evidenceStatus", "pending");
            summary.put("workersBound", !LaneCase.workersAvailable(workerSamples));
            if (settings.referenceLane() && !LaneCase.workersAvailable(workerSamples)) invalid.add("workers-exhausted");
            if (completed.values().stream().anyMatch(count -> count >= settings.items())) invalid.add("items-exhausted");
            summary.put("status", invalid.isEmpty() ? "passed" : "invalid");
            summary.put("stage", "complete");
        } catch (Exception error) {
            failure = error;
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
            if (error instanceof CallApi.ActionFailure action) {
                summary.put("httpStatus", action.httpStatus);
                if (action.errorCode != null) summary.put("errorCode", action.errorCode);
            }
        } finally {
            summary.put("invalidReasons", invalid);
            summary.put("workerSamples", workerSamples);
            summary.put("leaseHeldPeakRatio", LaneCase.leaseHeldPeakRatio(workerSamples, settings.workers()));
            Files.writeString(output.resolve("case.json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Lane saturation case failed; inspect safe summary", failure);
    }

    private static String createTask(CallApi api, String group, Path path) throws Exception {
        var refill = path == Path.TASK_ANY
                ? List.of(Map.of("poolName", "any", "target", Map.of(), "count", LaneCase.ASSIGNMENT_BATCH_LIMIT))
                : List.of();
        return CallApi.string(api.post("/api/v1/tasks", Map.of("projectId", LaneWorld.PROJECT, "workerGroupId", group,
                "refill", refill, "priority", 50, "maxRetryTimes", 3)), "taskId");
    }

    /** One append at a time per Task; independent Tasks seed concurrently, all before approval. */
    static void seed(CallApi api, Path path, Map<String, List<String>> world, Map<String, String> tasks,
                             String prefix, ExperimentConfig.Settings settings) throws Exception {
        var failure = new AtomicReference<Exception>();
        try (var execution = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String group : LaneWorld.GROUPS) {
                var ids = world.get(group);
                execution.execute(() -> {
                    try {
                        for (int offset = 0; offset < settings.items() && failure.get() == null; offset += APPEND_BATCH) {
                            appendBatch(api, tasks.get(group), items(path, group, ids, prefix, offset, settings.ttlMillis()));
                        }
                    } catch (Exception error) {
                        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                        failure.compareAndSet(null, error);
                    }
                });
            }
        }
        if (failure.get() instanceof CallApi.ActionFailure action) throw action;
        if (failure.get() != null)
            throw new IllegalStateException("Saturation seeding failed: " + failure.get().getClass().getSimpleName(), failure.get());
    }

    static List<Map<String, Object>> items(Path path, String group, List<String> ids, String prefix, int first) {
        return items(path, group, ids, prefix, first, 900_000);
    }

    static List<Map<String, Object>> items(Path path, String group, List<String> ids, String prefix, int first, long ttlMillis) {
        var items = new ArrayList<Map<String, Object>>(APPEND_BATCH);
        for (int index = first; index < first + APPEND_BATCH; index++) {
            Map<String, Object> selector = path == Path.TASK_ANY
                    ? Map.of("executorName", "worker.any", "input", Map.of())
                    : Map.of("executorName", "workerId", "input", ids.get(index % ids.size()));
            items.add(Map.of("messageId", prefix + "-" + group + "-" + index, "eventCode", "extension.worker.string.md5",
                    "payload", Map.of("value", CallApi.INPUT), "workerSelector", selector, "ttlMillis", ttlMillis));
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

}
