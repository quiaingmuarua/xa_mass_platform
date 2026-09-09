package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Caller-targeted Direct Call fixture; there is no Task submission or Result drain. */
final class DirectCallPerformance {
    static final int WORKERS = 1_000;
    static final Map<String, Integer> CASES = Map.of("direct-100", 100, "direct-500", 500,
            "direct-1000", 1_000, "direct-2000", 2_000, "direct-5000", 5_000,
            "direct-step-1000", 1_000, "direct-step-2000", 2_000);

    private DirectCallPerformance() {}

    static void run(String name, Map<String, String> options) throws Exception {
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        int seconds = name.startsWith("direct-step-") ? 120 : 30;
        summary.put("fixtureVersion", seconds == 120 ? 2 : 1);
        summary.put("callPath", "DIRECT_CALL");
        summary.put("case", name);
        summary.put("workers", WORKERS);
        summary.put("offeredRate", CASES.get(name));
        summary.put("measurementSeconds", seconds);
        summary.put("waitTimeoutMillis", 1_000);
        summary.put("followupAvailable", false);
        summary.put("status", "failed");
        summary.put("phase", "bootstrap");
        CallLoad.Batch warmup = null;
        CallLoad.Batch measured = null;
        Exception failure = null;
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> ids = readyWorkers(api);
            summary.put("workerIds", ids);
            String prefix = UUID.randomUUID().toString();
            summary.put("phase", "warmup");
            warmup = CallLoad.schedule(100, 20, 4_096, prefix + "-warmup", executor,
                    (id, index) -> api.directCall(ids.get(index % WORKERS)));
            warmup.await();
            requireValid(warmup);
            if (warmup.samples().stream().anyMatch(s -> s.outcome != CallLoad.Outcome.SUCCEEDED))
                throw new IllegalStateException("Direct warmup did not return all 2000 successes");
            requireNetwork(api, ids);
            summary.put("phase", "measurement");
            summary.put("measurementStartedEpochMillis", System.currentTimeMillis());
            measured = CallLoad.schedule(CASES.get(name), seconds, 4_096, prefix + "-measured", executor,
                    (id, index) -> api.directCall(ids.get(index % WORKERS)));
            measured.await();
            requireValid(measured);
            var callIds = new HashSet<String>();
            for (var batch : List.of(warmup, measured)) for (var sample : batch.samples())
                if (sample.directCallId != null && !callIds.add(sample.directCallId))
                    throw new CallLoad.ProtocolFailure("Direct Call ID was reused");
            summary.put("phase", "network-after-measurement");
            requireNetwork(api, ids);
            summary.put("status", "passed");
            summary.put("phase", "complete");
        } catch (Exception error) {
            failure = error;
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
        } finally {
            if (warmup != null) {
                summary.put("warmup", summarize(warmup));
                writeSamples(output.resolve("warmup-samples.jsonl"), warmup);
            }
            if (measured != null) {
                summary.putAll(summarize(measured));
                if (seconds == 120) {
                    summary.put("windows", Map.of("surge", summarize(measured, 0, 30),
                            "sustained", summarize(measured, 30, 120)));
                    var buckets = new ArrayList<Map<String, Object>>();
                    for (int offset = 0; offset < seconds; offset += 5)
                        buckets.add(summarize(measured, offset, offset + 5));
                    summary.put("fiveSecondBuckets", buckets);
                }
                writeSamples(output.resolve("samples.jsonl"), measured);
            }
            Files.writeString(output.resolve("summary.json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Direct performance case failed; inspect safe summary", failure);
    }

    static Map<String, Object> summarize(CallLoad.Batch batch) {
        return summarize(batch, 0, (int) (batch.windowNanos() / 1_000_000_000L));
    }

    static Map<String, Object> summarize(CallLoad.Batch batch, int fromSeconds, int toSeconds) {
        var result = batch.responseSummary(fromSeconds * 1_000_000_000L, toSeconds * 1_000_000_000L);
        var cohort = batch.cohort(fromSeconds * 1_000_000_000L, toSeconds * 1_000_000_000L);
        long sent = ((Number) result.get("sent")).longValue();
        long timely = cohort.stream().filter(s -> s.outcome == CallLoad.Outcome.SUCCEEDED
                && s.ended - s.planned <= 1_000_000_000L).count();
        result.put("http200", cohort.stream().filter(s -> s.httpStatus == 200).count());
        result.put("successfulWithinOneSecond", timely);
        result.put("withinOneSecondRateOfSent", sent == 0 ? 0.0 : (double) timely / sent);
        result.put("withinOneSecondRateOfPlanned", cohort.isEmpty() ? 0.0 : (double) timely / cohort.size());
        var details = cohort.stream().filter(s -> s.detail != null)
                .collect(Collectors.groupingBy(s -> s.detail, java.util.TreeMap::new, Collectors.counting()));
        result.put("details", details);
        var rates = new LinkedHashMap<String, Object>();
        for (var outcome : CallLoad.Outcome.values()) {
            if (outcome == CallLoad.Outcome.NOT_SENT) continue;
            long count = cohort.stream().filter(s -> s.outcome == outcome).count();
            rates.put(outcome.name().toLowerCase(java.util.Locale.ROOT), sent == 0 ? null : count / (double) sent);
        }
        rates.put("command-slot-occupied", sent == 0 ? null : details.getOrDefault("command-slot-occupied", 0L) / (double) sent);
        rates.put("http-429", sent == 0 ? null : details.getOrDefault("http-429", 0L) / (double) sent);
        result.put("outcomeRatesOfSent", rates);
        result.put("notSentRateOfPlanned", cohort.isEmpty() ? null : (cohort.size() - sent) / (double) cohort.size());
        return result;
    }

    static void requireValid(CallLoad.Batch batch) {
        if (batch.samples().stream().anyMatch(s -> s.outcome == CallLoad.Outcome.PROTOCOL_ERROR
                || s.outcome == CallLoad.Outcome.NOT_OBSERVED))
            throw new CallLoad.ProtocolFailure("Invalid Direct response or Task status leaked into Direct fixture");
    }

    private static List<String> readyWorkers(CallApi api) throws Exception {
        long deadline = System.nanoTime() + 180_000_000_000L;
        do {
            Object raw = api.workers().get("workers");
            if (!(raw instanceof List<?> workers) || workers.size() != WORKERS)
                throw new CallLoad.ProtocolFailure("Expected exactly 1000 Lab Workers");
            var ids = new ArrayList<String>();
            for (var rawWorker : workers) {
                var worker = CallApi.object(rawWorker);
                if (!CallApi.GROUP.equals(worker.get("workerGroupId"))) throw new CallLoad.ProtocolFailure("Unexpected Lab Group");
                if ("RUNNING".equals(worker.get("runtimeState")) && worker.get("workerId") instanceof String id) ids.add(id);
            }
            if (ids.size() == WORKERS && Set.copyOf(ids).size() == WORKERS) {
                var ordered = ids.stream().sorted().toList();
                if (networkConnected(api, ordered)) return ordered;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("1000 distinct Workers did not connect");
    }

    private static void requireNetwork(CallApi api, List<String> ids) throws Exception {
        if (!networkConnected(api, ids)) throw new IllegalStateException("Known Worker route disconnected");
    }

    private static boolean networkConnected(CallApi api, List<String> ids) throws Exception {
        for (int offset = 0; offset < ids.size(); offset += 100) {
            var page = ids.subList(offset, Math.min(offset + 100, ids.size()));
            var states = CallApi.object(api.post("/api/v1/runtime-view/endpoint-managers/scenario-websocket/workers:network-observe", page)
                    .get("statesByWorkerId"));
            if (!states.keySet().equals(Set.copyOf(page))) throw new CallLoad.ProtocolFailure("Network identity set changed");
            if (states.values().stream().anyMatch(s -> !"connected".equals(s))) return false;
        }
        return true;
    }

    private static void writeSamples(Path path, CallLoad.Batch batch) throws Exception {
        try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
            for (var sample : batch.samples()) {
                var row = sample.evidence(batch.started());
                row.put("requestId", row.remove("messageId"));
                row.remove("accepted");
                row.remove("observedResult");
                row.remove("observedAfterWaitMillis");
                writer.write(Jsons.toJson(row));
                writer.newLine();
            }
        }
    }
}
