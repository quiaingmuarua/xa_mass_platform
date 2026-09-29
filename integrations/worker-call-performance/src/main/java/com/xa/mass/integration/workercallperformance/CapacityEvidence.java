package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

/** Counts existing Owner completion events, then reconciles them with verified public exports. */
final class CapacityEvidence {
    record Completion(long at, long count) {}
    static final class Counts {
        final List<Completion> successes = new ArrayList<>();
        final List<Long> coverage = new ArrayList<>();
        final Map<String, Long> totals = new LinkedHashMap<>();
        final Map<String, Long> windowStages = new LinkedHashMap<>();
        final LaneAttribution.Totals dispatch = new LaneAttribution.Totals();
        boolean dataLoss, failed;
        long gcCount, gcNanos;

        void add(String type, String stage, String key, boolean error, long count, int batch,
                 long elapsed, long at, long lifeStart, long lifeEnd, long start, long end) {
            if (!key.isEmpty() || at < lifeStart || at > lifeEnd) return;
            boolean selected = (type.equals("xa.mass.TaskSubmission") && stage.equals("RESULT_STORED"))
                    || (type.equals("xa.mass.TaskDispatch") && List.of("CLAIMED", "COMMAND_PUBLISHED").contains(stage));
            if (selected) {
                if (error) failed = true;
                else {
                    totals.merge(stage, count, Long::sum);
                    if (stage.equals("RESULT_STORED")) successes.add(new Completion(at, count));
                }
            }
            if ((stage.equals("RESULT_FAILURE_DECODE") || stage.equals("FAILED_RESULT_STORED")) && count > 0)
                failed = true;
            if (at >= start && at < end) {
                if (type.equals("xa.mass.TaskDispatch")) dispatch.add(stage, batch, (int) count, elapsed);
                if (!error) windowStages.merge(stage, count, Long::sum);
                if (error && List.of("RESULT_PROCESS", "WORKER_RELEASE", "DISPATCH_ROUND", "REFILL_ROUND").contains(stage))
                    failed = true;
            }
        }
        long between(long start, long end) {
            return successes.stream().filter(e -> e.at() >= start && e.at() < end).mapToLong(Completion::count).sum();
        }
    }

    private CapacityEvidence() {}

    static void run(Map<String, String> options) throws Exception {
        Path input = Path.of(options.get("--input"));
        var value = new LinkedHashMap<>(Jsons.parseObject(Files.readString(input)));
        var result = analyze(Path.of(options.get("--recording")), value,
                options.containsKey("--resources") ? Path.of(options.get("--resources")) : null);
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        Files.writeString(output.resolve("capacity-case.json"), Jsons.toJson(result));
    }

    static Map<String, Object> analyze(Path recording, Map<String, Object> value, Path resources) throws Exception {
        var result = new LinkedHashMap<>(value);
        var reasons = new ArrayList<String>();
        if (value.get("invalidReasons") instanceof List<?> list) list.forEach(r -> reasons.add(String.valueOf(r)));
        if (!value.containsKey("validationEndedEpochMillis")) {
            result.put("evidenceStatus", "incomplete");
            return result;
        }
        long lifeStart = number(value, "lifecycleStartedEpochMillis");
        long lifeEnd = number(value, "validationEndedEpochMillis");
        long start = number(value, "measurementStartedEpochMillis");
        long end = number(value, "measurementEndedEpochMillis");
        var counts = new Counts();
        if (!Files.isRegularFile(recording) || Files.size(recording) > LaneAttribution.MAX_RECORDING_BYTES)
            reasons.add("recording-missing-or-oversized");
        else {
            try (var reader = new RecordingFile(recording)) {
                while (reader.hasMoreEvents()) {
                    var event = reader.readEvent();
                    String type = event.getEventType().getName();
                    long at = event.getStartTime().toEpochMilli();
                    if (type.equals("jdk.DataLoss")) counts.dataLoss = true;
                    if (type.equals("jdk.CPULoad")) counts.coverage.add(at);
                    if (type.equals("jdk.GarbageCollection") && at >= start && at < end) {
                        counts.gcCount++;
                        counts.gcNanos += event.getDuration().toNanos();
                    }
                    if (!List.of("xa.mass.TaskDispatch", "xa.mass.TaskSubmission", "xa.mass.TaskResult").contains(type)
                            || !event.hasField("key")) continue;
                    counts.add(type, event.getString("stage"), event.getString("key"), event.getBoolean("failed"),
                            event.getInt("count"), event.getInt("batchSize"), event.getLong("elapsedNanos"),
                            at, lifeStart, lifeEnd, start, end);
                }
            }
        }
        validate(counts, number(value, "exportedSuccessCount"), lifeStart, lifeEnd, reasons);
        long completed = counts.between(start, end);
        double seconds = (end - start) / 1000.0;
        result.put("completedCount", completed);
        result.put("completedPerSecond", completed / seconds);
        result.put("throughput10Seconds", buckets(counts, start, end, 10_000));
        result.put("acceptance30Seconds", buckets(counts, start, end, 30_000));
        result.put("reconciledCounts", counts.totals);
        result.put("windowStageCounts", counts.windowStages);
        result.put("attribution", counts.dispatch.summary(seconds));
        result.put("gc", Map.of("count", counts.gcCount, "durationMillis", counts.gcNanos / 1e6));
        result.put("resourceCosts", resources == null ? Map.of() : costs(resources, counts, start, end));
        result.put("invalidReasons", reasons);
        result.put("evidenceStatus", reasons.isEmpty() ? "complete" : "incomplete");
        if (!"failed".equals(result.get("status"))) result.put("status", reasons.isEmpty() ? "passed" : "invalid");
        return result;
    }

    static void validate(Counts counts, long exported, long start, long end, List<String> reasons) {
        if (counts.dataLoss) reasons.add("jfr-data-loss");
        if (counts.failed) reasons.add("execution-or-owner-failure");
        for (String stage : List.of("CLAIMED", "COMMAND_PUBLISHED", "RESULT_STORED"))
            if (exported <= 0 || counts.totals.getOrDefault(stage, 0L) != exported)
                reasons.add("count-mismatch-" + stage.toLowerCase(java.util.Locale.ROOT));
        var times = counts.coverage.stream().sorted().toList();
        if (times.isEmpty() || times.getFirst() > start + 2000 || times.getLast() < end - 2000)
            reasons.add("recording-coverage");
        for (int i = 1; i < times.size(); i++)
            if (times.get(i) >= start && times.get(i - 1) <= end && times.get(i) - times.get(i - 1) > 3000) {
                reasons.add("recording-coverage-gap"); break;
            }
    }

    static List<Map<String, Object>> buckets(Counts counts, long start, long end, long width) {
        var rows = new ArrayList<Map<String, Object>>();
        for (long first = start; first < end; first += width) {
            long last = Math.min(end, first + width), n = counts.between(first, last);
            rows.add(Map.of("offsetSeconds", (first - start) / 1000.0, "seconds", (last - first) / 1000.0,
                    "completedCount", n, "completedPerSecond", n * 1000.0 / (last - first)));
        }
        return rows;
    }

    static Map<String, Object> costs(Path path, Counts counts, long start, long end) throws Exception {
        if (!Files.isRegularFile(path)) return Map.of();
        var first = new LinkedHashMap<String, Map<String, Object>>();
        var last = new LinkedHashMap<String, Map<String, Object>>();
        var incomplete = new java.util.HashSet<String>();
        try (var lines = Files.lines(path)) {
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                var row = Jsons.parseObject(iterator.next());
                long at = number(row, "epochMillis");
                if (at < start || at >= end) continue;
                String role = CallApi.string(row, "role");
                if (!List.of("server", "host", "redis").contains(role)) continue;
                if (last.containsKey(role) && (at <= number(last.get(role), "epochMillis")
                        || at - number(last.get(role), "epochMillis") > 3000)) incomplete.add(role);
                first.putIfAbsent(role, row);
                last.put(role, row);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        first.forEach((role, initial) -> {
            var terminal = last.get(role);
            long from = number(initial, "epochMillis"), to = number(terminal, "epochMillis");
            long completed = counts.between(from, to);
            if (to <= from || completed == 0 || from > start + 2000 || to < end - 2000 || incomplete.contains(role)) return;
            double cpu = role.equals("redis")
                    ? decimal(terminal, "cpuUserSeconds") + decimal(terminal, "cpuSystemSeconds")
                        - decimal(initial, "cpuUserSeconds") - decimal(initial, "cpuSystemSeconds")
                    : decimal(terminal, "cpuSeconds") - decimal(initial, "cpuSeconds");
            var cost = new LinkedHashMap<String, Object>();
            cost.put("coveredSeconds", (to - from) / 1000.0);
            cost.put("completedCount", completed);
            cost.put("cpuSeconds", cpu);
            cost.put("averageCpuCores", cpu * 1000 / (to - from));
            cost.put("cpuMillisPerCompleted", cpu * 1000 / completed);
            if (role.equals("redis")) {
                long commands = number(terminal, "totalCommandsProcessed") - number(initial, "totalCommandsProcessed");
                cost.put("commands", commands);
                cost.put("commandsPerCompleted", commands / (double) completed);
            }
            result.put(role, cost);
        });
        return result;
    }

    static long number(Map<String, Object> value, String key) { return ((Number) value.get(key)).longValue(); }
    static double decimal(Map<String, Object> value, String key) { return ((Number) value.get(key)).doubleValue(); }
}
