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

public final class WorkerCallPerformanceMain {
    static final Map<String, Integer> CASES = Map.of("any-100", 100, "any-500", 500,
            "any-1000", 1_000, "any-2000", 2_000, "targeted-500", 500, "mixed-500", 500);
    private WorkerCallPerformanceMain() {}

    public static void main(String[] args) throws Exception {
        var options = new LinkedHashMap<String, String>();
        for (String arg : args) {
            var pair = arg.split("=", 2);
            if (pair.length != 2 || !Set.of("--case", "--output", "--runtime-url", "--lab-url").contains(pair[0])
                    || options.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException("Invalid option");
        }
        String name = options.get("--case");
        if (DirectCallPerformance.CASES.containsKey(name) && options.containsKey("--output")) {
            DirectCallPerformance.run(name, options);
            return;
        }
        if (!CASES.containsKey(name) || !options.containsKey("--output")) throw new IllegalArgumentException("case and output required");
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("fixtureVersion", 1);
        summary.put("case", name);
        summary.put("offeredRate", CASES.get(name));
        summary.put("measurementSeconds", 30);
        summary.put("waitTimeoutMillis", 1_000);
        summary.put("itemTtlMillis", 120_000);
        summary.put("status", "failed");
        summary.put("phase", "bootstrap");
        CallLoad.Batch measured = null;
        CallLoad.Batch warmup = null;
        Exception failure = null;
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"));
             var execution = Executors.newVirtualThreadPerTaskExecutor()) {
            var ids = readyWorkers(api);
            var registration = api.post("/api/v1/worker-groups/" + CallApi.GROUP + ":register", Map.of(
                    "attributes", Map.of("capability", "string-utils"),
                    "eventCodes", List.of("extension.worker.string.md5", "extension.worker.lab.delay")));
            if (!CallApi.GROUP.equals(registration.get("workerGroupId")))
                throw new CallLoad.ProtocolFailure("Registration Group changed");
            String task = CallApi.string(registration, "taskId");
            String prefix = UUID.randomUUID().toString();
            summary.put("phase", "warmup");
            warmup = CallLoad.schedule(100, 20, 4_096, prefix + "-warmup", execution,
                    (id, index) -> api.call(task, id, null));
            warmup.await();
            settle(api, task, warmup, 180);
            requireHealthy(warmup);
            if (warmup.samples().stream().anyMatch(s -> !s.observed.equals("succeeded")))
                throw new IllegalStateException("Warmup did not succeed");
            summary.put("phase", "background-preparation");
            String background = name.equals("mixed-500") ? createBackground(api, prefix) : null;
            var monitorFailure = new AtomicReference<Exception>();
            var watching = new AtomicBoolean(background != null);
            Thread monitor = background == null ? null : Thread.ofVirtual().name("background-witness").start(() -> {
                while (watching.get()) {
                    try { requireBackground(api, background, prefix); Thread.sleep(1_000); }
                    catch (Exception error) { if (watching.get()) monitorFailure.set(error); return; }
                }
            });
            try {
                summary.put("phase", "measurement");
                summary.put("measurementStartedEpochMillis", System.currentTimeMillis());
                measured = CallLoad.schedule(CASES.get(name), 30, 4_096, prefix + "-measured", execution,
                        (id, index) -> api.call(task, id, name.startsWith("targeted") ? ids.get(index % ids.size()) : null));
                measured.await();
                if (background != null) requireBackground(api, background, prefix);
            } finally {
                watching.set(false);
                if (monitor != null) {
                    monitor.interrupt(); monitor.join(6_000);
                    if (monitor.isAlive()) throw new IllegalStateException("Background observer did not stop");
                }
            }
            if (monitorFailure.get() != null) throw monitorFailure.get();
            summary.put("phase", "drain");
            settle(api, task, measured, 180);
            requireHealthy(measured);
            summary.put("status", "passed");
            summary.put("phase", "complete");
        } catch (Exception error) {
            failure = error;
            // Error class is sufficient for safe evidence; process logs retain only local diagnostics.
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
        } finally {
            if (warmup != null) {
                summary.put("warmup", warmup.summary());
                writeSamples(output.resolve("warmup-samples.jsonl"), warmup);
            }
            if (measured != null) {
                summary.putAll(measured.summary());
                writeSamples(output.resolve("samples.jsonl"), measured);
            }
            Files.writeString(output.resolve("summary.json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Performance case failed; inspect safe summary", failure);
    }

    static void settle(CallApi api, String task, CallLoad.Batch batch, int seconds) throws Exception {
        long started = System.nanoTime();
        long deadline = started + seconds * 1_000_000_000L;
        do {
            var pending = batch.samples().stream().filter(s -> s.sent != 0 && s.observed.equals("not_observed")
                    && s.outcome != CallLoad.Outcome.PROTOCOL_ERROR).toList();
            if (pending.isEmpty()) return;
            for (int offset = 0; offset < pending.size() && System.nanoTime() < deadline; offset += 1_000) {
                var page = pending.subList(offset, Math.min(offset + 1_000, pending.size()));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                Map<String, String> results;
                try {
                    results = api.results(task, page.stream().map(s -> s.id).toList(), CallApi.ExpectedResult.MD5,
                            java.time.Duration.ofNanos(Math.min(5_000_000_000L, remaining)));
                } catch (java.net.http.HttpTimeoutException timeout) {
                    if (System.nanoTime() >= deadline) return;
                    throw timeout;
                }
                for (var sample : page) {
                    String result = results.get(sample.id);
                    if (!result.equals("not_observed")) {
                        sample.observed = result;
                        sample.observedAfterWaitMillis = (System.nanoTime() - started) / 1_000_000;
                    }
                }
            }
            if (batch.samples().stream().noneMatch(s -> s.accepted() && s.observed.equals("not_observed"))) return;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) Thread.sleep(java.time.Duration.ofNanos(Math.min(500_000_000L, remaining)));
        } while (System.nanoTime() < deadline);
    }

    static void requireHealthy(CallLoad.Batch batch) {
        if (batch.samples().stream().anyMatch(s -> s.outcome == CallLoad.Outcome.PROTOCOL_ERROR))
            throw new CallLoad.ProtocolFailure("Invalid call response");
        if (batch.samples().stream().anyMatch(s -> s.accepted() && s.observed.equals("not_observed")))
            throw new IllegalStateException("Accepted Items remain unobserved after drain budget");
    }

    private static List<String> readyWorkers(CallApi api) throws Exception {
        long deadline = System.nanoTime() + 180_000_000_000L;
        do {
            Object raw = api.workers().get("workers");
            if (!(raw instanceof List<?> workers)) throw new CallLoad.ProtocolFailure("Missing Lab workers");
            var ids = new ArrayList<String>();
            for (var entry : workers) {
                var worker = CallApi.object(entry);
                if (!CallApi.GROUP.equals(worker.get("workerGroupId"))) throw new CallLoad.ProtocolFailure("Wrong Lab Group");
                if ("RUNNING".equals(worker.get("runtimeState")) && worker.get("workerId") instanceof String id) ids.add(id);
            }
            if (ids.size() == 100 && Set.copyOf(ids).size() == 100) {
                boolean network = states(api, "/api/v1/runtime-view/endpoint-managers/scenario-websocket/workers:network-observe", ids, Set.of("connected"));
                boolean scheduling = states(api, "/api/v1/runtime-view/worker-groups/" + CallApi.GROUP + "/workers:scheduling-observe", ids, Set.of("held-hot", "hot-score-overdue"));
                var preview = api.post("/api/v1/runtime-view/worker-groups/" + CallApi.GROUP + "/workers:preview", 100);
                Object entries = preview.get("workers");
                boolean facts = entries instanceof List<?> list && list.size() == 100 && list.stream().allMatch(e -> {
                    var worker = CallApi.object(e);
                    return ids.contains(worker.get("workerId")) && worker.get("workerProperties") instanceof Map<?, ?> map && !map.isEmpty();
                }) && list.stream().map(e -> CallApi.object(e).get("workerId"))
                        .collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(ids));
                if (network && scheduling && facts) return ids.stream().sorted().toList();
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("100 Workers did not become ready with facts");
    }

    private static boolean states(CallApi api, String path, List<String> ids, Set<String> expected) throws Exception {
        var states = CallApi.object(api.post(path, ids).get("statesByWorkerId"));
        if (!states.keySet().equals(Set.copyOf(ids))) throw new CallLoad.ProtocolFailure("Worker observation identity changed");
        return states.values().stream().allMatch(expected::contains);
    }

    private static String createBackground(CallApi api, String prefix) throws Exception {
        String task = CallApi.string(api.post("/api/v1/tasks", Map.of("workerGroupId", CallApi.GROUP,
                "allocationRule", Map.of(), "priority", 50, "maximumCandidateWorkers", 50, "maxRetryTimes", 3)), "taskId");
        for (int offset = 0; offset < 50_000; offset += 100) {
            var items = new ArrayList<Map<String, Object>>();
            for (int i = offset; i < offset + 100; i++) items.add(Map.of("messageId", prefix + "-bg-" + i,
                    "eventCode", "extension.worker.lab.delay", "payload", Map.of("delayMillis", 100), "ttlMillis", 600_000));
            var appended = api.post("/api/v1/tasks/" + task + "/items", items);
            if (!appended.keySet().equals(items.stream().map(i -> i.get("messageId")).collect(java.util.stream.Collectors.toSet()))
                    || appended.values().stream().anyMatch(v -> !"applied".equals(CallApi.object(v).get("status"))))
                throw new CallLoad.ProtocolFailure("Background append did not apply exact Item set");
        }
        var approval = api.post("/api/v1/tasks/" + task + "/approve", Map.of());
        if (!Set.of("applied", "unchanged").contains(approval.get("status"))) throw new CallLoad.ProtocolFailure("Task approval failed");
        long deadline = System.nanoTime() + 60_000_000_000L;
        var witness = backgroundWitnessIds(prefix);
        do {
            if (api.results(task, witness, CallApi.ExpectedResult.DELAY).containsValue("succeeded")) { requireBackground(api, task, prefix); return task; }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Background did not start");
    }

    private static void requireBackground(CallApi api, String task, String prefix) throws Exception {
        Object raw = api.post("/api/v1/runtime-view/tasks:preview", 100).get("entries");
        if (!(raw instanceof List<?> entries)) throw new CallLoad.ProtocolFailure("Missing Task preview");
        var matching = entries.stream().map(CallApi::object).filter(e -> task.equals(e.get("taskId"))).toList();
        if (matching.size() != 1 || "terminal".equals(matching.getFirst().get("scoreBand")))
            throw new IllegalStateException("Background Task missing or terminal during measurement");
        if (!api.results(task, backgroundWitnessIds(prefix), CallApi.ExpectedResult.DELAY).containsValue("not_observed"))
            throw new IllegalStateException("Background unfinished-work witness exhausted");
    }

    private static List<String> backgroundWitnessIds(String prefix) {
        // Span all append pages: TaskItem scheduling does not promise insertion order.
        return java.util.stream.IntStream.range(0, 1_000).mapToObj(i -> prefix + "-bg-" + (i * 50)).toList();
    }

    private static void writeSamples(Path path, CallLoad.Batch batch) throws Exception {
        try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var sample : batch.samples()) { writer.write(Jsons.toJson(sample.evidence(batch.started()))); writer.newLine(); }
        }
    }
}
