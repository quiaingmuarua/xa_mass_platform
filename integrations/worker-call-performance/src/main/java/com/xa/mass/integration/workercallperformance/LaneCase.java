package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One open-loop performance-lane case: a fixed path and total offered rate split evenly across
 * the two lane Groups. Status is {@code passed}, {@code invalid} (a resource or the generator was
 * the binding constraint) or {@code failed} (a correctness floor or fast-fail rule was violated).
 */
final class LaneCase {
    enum Path { TASK_ANY, TASK_TARGETED, DIRECT;
        String label() { return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    }
    static final List<Integer> RATES = List.of(500, 1_000, 2_000);
    static final int WARMUP_RATE = 100;
    static final int WARMUP_SECONDS = 15;
    static final int MEASUREMENT_SECONDS = 30;
    static final int MAX_IN_FLIGHT = 4_096;
    static final int ASSIGNMENT_BATCH_LIMIT = 1_000;
    static final long SAMPLE_INTERVAL_MILLIS = 5_000;
    static final long DRAIN_STALL_NANOS = 30_000_000_000L;
    static final long DRAIN_PROJECTION_AFTER_NANOS = 10_000_000_000L;

    record Spec(Path path, int rate) {
        static Spec parse(String name) {
            for (Path path : Path.values())
                for (int rate : RATES)
                    if (name.equals(path.label() + "-" + rate)) return new Spec(path, rate);
            throw new IllegalArgumentException("Unknown lane case");
        }
        String name() { return path.label() + "-" + rate; }
    }

    /** Fast-fail or correctness failure with a stable reason code for safe evidence. */
    static final class Failed extends IllegalStateException {
        final String reason;
        Failed(String reason, String message) { super(message); this.reason = reason; }
    }

    private LaneCase() {}

    static void run(Map<String, String> options) throws Exception {
        var spec = Spec.parse(options.get("--case"));
        java.nio.file.Path output = java.nio.file.Path.of(options.get("--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("phase", "case");
        summary.put("case", spec.name());
        summary.put("path", spec.path().label());
        summary.put("offeredRate", spec.rate());
        summary.put("repetition", Integer.parseInt(options.getOrDefault("--repetition", "1")));
        summary.put("measurementSeconds", MEASUREMENT_SECONDS);
        summary.put("status", "failed");
        CallLoad.Batch warmup = null;
        CallLoad.Batch measured = null;
        var workerSamples = new ArrayList<Map<String, Object>>();
        var invalid = new ArrayList<String>();
        Exception failure = null;
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"));
             var execution = Executors.newVirtualThreadPerTaskExecutor()) {
            var world = LaneWorld.readWorld(java.nio.file.Path.of(options.get("--world")));
            var tasks = LaneWorld.readTasks(java.nio.file.Path.of(options.get("--world")));
            String prefix = UUID.randomUUID().toString();
            summary.put("stage", "warmup");
            warmup = CallLoad.schedule(WARMUP_RATE, WARMUP_SECONDS, MAX_IN_FLIGHT, prefix + "-warmup", execution,
                    sender(api, spec.path(), world, tasks));
            warmup.await();
            if (spec.path() != Path.DIRECT) drain(api, tasks, warmup, 180);
            if (warmup.samples().stream().anyMatch(s -> !s.observed.equals("succeeded")))
                throw new Failed("warmup", "Warmup calls did not all succeed");

            summary.put("stage", "measurement");
            var stop = new AtomicReference<String>();
            var sampling = new AtomicBoolean(true);
            long started = System.currentTimeMillis();
            summary.put("measurementStartedEpochMillis", started);
            Thread sampler = Thread.ofVirtual().name("lane-worker-sampler").start(() -> {
                while (sampling.get()) {
                    try {
                        workerSamples.add(sampleWorkers(api, world, System.currentTimeMillis() - started));
                        Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                    } catch (InterruptedException stopped) {
                        return;
                    } catch (Exception unavailable) {
                        workerSamples.add(Map.of("offsetMillis", System.currentTimeMillis() - started, "unavailable", true));
                    }
                }
            });
            try {
                measured = CallLoad.schedule(spec.rate(), MEASUREMENT_SECONDS, MAX_IN_FLIGHT, prefix + "-measured",
                        execution, sender(api, spec.path(), world, tasks), stop);
                measured.await();
            } finally {
                sampling.set(false);
                sampler.interrupt();
                sampler.join(10_000);
            }
            summary.put("measurementEndedEpochMillis", System.currentTimeMillis());
            if ("protocol-error".equals(stop.get())) throw new Failed("protocol-error", "Invalid call response");
            if (stop.get() != null) {
                summary.put("fastFail", stop.get());
                invalid.add(stop.get());
            }
            if (spec.path() != Path.DIRECT) {
                summary.put("stage", "drain");
                long accepted = measured.samples().stream().filter(CallLoad.Sample::accepted).count();
                int budget = WorkerCallPerformanceMain.drainBudgetSeconds(spec.rate(), accepted, ASSIGNMENT_BATCH_LIMIT);
                summary.put("drainBudgetSeconds", budget);
                drain(api, tasks, measured, budget);
                if (measured.samples().stream().anyMatch(s -> s.accepted() && s.observed.equals("not_observed")))
                    throw new Failed("drain-budget", "Accepted Items remain unobserved after the drain budget");
            }
            if (measured.samples().stream().anyMatch(s -> s.outcome == CallLoad.Outcome.PROTOCOL_ERROR))
                throw new Failed("protocol-error", "Invalid call response");
            if (Boolean.TRUE.equals(measured.summary().get("generatorLimited")) && !invalid.contains("generator-limited"))
                invalid.add("generator-limited");
            if (!workersSufficient(workerSamples)) invalid.add("workers-insufficient");
            summary.put("status", invalid.isEmpty() ? "passed" : "invalid");
            summary.put("stage", "complete");
        } catch (Exception error) {
            failure = error;
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof Failed failed) summary.put("failureReason", failed.reason);
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
        } finally {
            summary.put("invalidReasons", invalid);
            summary.put("workerSamples", workerSamples);
            if (warmup != null) summary.put("warmup", warmup.responseSummary());
            if (measured != null) {
                var metrics = measured.summary();
                metrics.remove("unresolvedAcceptedIds");
                summary.put("metrics", metrics);
                summary.put("completedPerSecond", completedPerSecond(measured));
                writeSamples(output.resolve("samples.jsonl"), measured);
            }
            Files.writeString(output.resolve("case.json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Lane case failed; inspect safe summary", failure);
    }

    /** Alternates Groups per planned call; targeted and Direct paths rotate through each Group's Workers. */
    static CallLoad.Sender sender(CallApi api, Path path, Map<String, List<String>> world, Map<String, String> tasks) {
        return (id, index) -> {
            var target = target(index, world);
            return switch (path) {
                case TASK_ANY -> api.call(tasks.get(target.group()), id, null);
                case TASK_TARGETED -> api.call(tasks.get(target.group()), id, target.worker());
                case DIRECT -> api.directCall(target.group(), target.worker());
            };
        };
    }

    record Target(String group, String worker) {}

    /** Planned call {@code index} goes to Group {@code index % 2} and that Group's next Worker. */
    static Target target(int index, Map<String, List<String>> world) {
        String group = LaneWorld.GROUPS.get(index % LaneWorld.GROUPS.size());
        var ids = world.get(group);
        return new Target(group, ids.get((index / LaneWorld.GROUPS.size()) % ids.size()));
    }

    /** Succeeded Results (after drain) per offered second; the open-loop completion throughput. */
    static double completedPerSecond(CallLoad.Batch batch) {
        long succeeded = batch.samples().stream().filter(s -> s.observed.equals("succeeded")).count();
        return succeeded / (batch.windowNanos() / 1e9);
    }

    /**
     * Workers are sufficient when every usable sample of every Group has at least twice as many
     * idle due HOT Workers as executing ones. An all-unavailable sampling run is not evidence.
     */
    static boolean workersSufficient(List<Map<String, Object>> samples) {
        var usable = samples.stream().filter(s -> !Boolean.TRUE.equals(s.get("unavailable"))).toList();
        if (usable.isEmpty()) return false;
        for (var sample : usable) {
            for (String group : LaneWorld.GROUPS) {
                var counts = CallApi.object(sample.get(group));
                long idle = ((Number) counts.getOrDefault("hot-score-overdue", 0L)).longValue();
                long held = ((Number) counts.getOrDefault("held-hot", 0L)).longValue();
                if (idle < 2 * held) return false;
            }
        }
        return true;
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

    /**
     * Observes accepted Results until all are observed or the budget ends. Fast-fails when no new
     * Result arrives for 30 seconds (stall) or, after 10 seconds, when the observed rate cannot
     * finish within the remaining budget (projection).
     */
    static void drain(CallApi api, Map<String, String> tasks, CallLoad.Batch batch, int seconds) throws Exception {
        long started = System.nanoTime();
        long deadline = started + seconds * 1_000_000_000L;
        long lastProgress = started;
        long initialPending = pending(batch).size();
        while (true) {
            var pending = pending(batch);
            if (pending.isEmpty()) return;
            long now = System.nanoTime();
            if (now >= deadline) return;
            if (now - lastProgress > DRAIN_STALL_NANOS)
                throw new Failed("drain-stalled", "No Result observed for 30 seconds with " + pending.size() + " pending");
            long resolved = initialPending - pending.size();
            if (now - started > DRAIN_PROJECTION_AFTER_NANOS && resolved > 0) {
                double perNano = resolved / (double) (now - started);
                if (pending.size() / perNano > deadline - now)
                    throw new Failed("drain-projected-late", "Observed Result rate cannot close within the budget");
            }
            boolean progressed = false;
            for (String group : LaneWorld.GROUPS) {
                var groupPending = pending.stream().filter(s -> group.equals(groupOf(s))).toList();
                for (int offset = 0; offset < groupPending.size(); offset += 1_000) {
                    var page = groupPending.subList(offset, Math.min(offset + 1_000, groupPending.size()));
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return;
                    var results = api.results(tasks.get(group), page.stream().map(s -> s.id).toList(),
                            CallApi.ExpectedResult.MD5, java.time.Duration.ofNanos(Math.min(5_000_000_000L, remaining)));
                    for (var sample : page) {
                        String result = results.get(sample.id);
                        if (!result.equals("not_observed")) {
                            sample.observed = result;
                            sample.observedAfterWaitMillis = (System.nanoTime() - started) / 1_000_000;
                            progressed = true;
                        }
                    }
                }
            }
            if (progressed) lastProgress = System.nanoTime();
            else Thread.sleep(500);
        }
    }

    private static List<CallLoad.Sample> pending(CallLoad.Batch batch) {
        return batch.samples().stream().filter(s -> s.sent != 0 && s.accepted() && s.observed.equals("not_observed")
                && s.outcome != CallLoad.Outcome.PROTOCOL_ERROR).toList();
    }

    /** Sample IDs end with their planned index; even indexes went to the first Group. */
    static String groupOf(CallLoad.Sample sample) {
        int index = Integer.parseInt(sample.id.substring(sample.id.lastIndexOf('-') + 1));
        return LaneWorld.GROUPS.get(index % LaneWorld.GROUPS.size());
    }

    private static void writeSamples(java.nio.file.Path path, CallLoad.Batch batch) throws Exception {
        try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var sample : batch.samples()) { writer.write(Jsons.toJson(sample.evidence(batch.started()))); writer.newLine(); }
        }
    }
}
