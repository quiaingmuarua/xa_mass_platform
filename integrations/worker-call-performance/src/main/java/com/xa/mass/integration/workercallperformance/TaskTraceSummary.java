package com.xa.mass.integration.workercallperformance;

import java.util.*;

/** Bounded offline same-JVM joins; no runtime registry and no per-Item journal is exported. */
final class TaskTraceSummary {
    static final int MAX_KEYS = 10_000;
    static final int MAX_EVENTS_PER_KEY = 64;
    private static final List<String> CHAIN = List.of("ITEM_INITIALIZED", "CLAIMED", "COMMAND_PUBLISHED", "RESULT_STORED", "OBSERVED");
    record Point(String stage, long from, long to, long epochStarted, boolean failed) {}
    private final Map<String, List<Point>> keys = new LinkedHashMap<>();
    private final Set<String> truncated = new HashSet<>();
    private long keyOverflowEvents, eventOverflow, invalid, duplicateEvents;

    void add(String key, Point point) {
        if (key == null || !key.matches("(?:00|40|80|c0)[0-9a-f]{62}") || point.to < point.from) { invalid++; return; }
        var events = keys.get(key);
        if (events == null) {
            if (keys.size() == MAX_KEYS) { keyOverflowEvents++; return; }
            events = new ArrayList<>();
            keys.put(key, events);
        }
        if (events.contains(point)) duplicateEvents++;
        if (events.size() == MAX_EVENTS_PER_KEY) { eventOverflow++; truncated.add(key); return; }
        events.add(point);
    }

    Map<String, Object> summarize(long started, int seconds) {
        var stages = new TreeMap<String, Long>();
        var missing = new TreeMap<String, Long>();
        var gaps = new LinkedHashMap<String, List<Long>>();
        for (String gap : List.of("initializedEndToClaimStart", "publishEndToResultWriteStart", "resultWriteEndToObservedStart"))
            gaps.put(gap, new ArrayList<>());
        long cohorts = 0, complete = 0, ambiguous = 0, overlaps = 0, timedOut = 0, overflowCohorts = 0;
        for (var entry : keys.entrySet()) {
            var events = entry.getValue();
            if (events.stream().noneMatch(p -> p.stage.equals("SUBMISSION") && p.epochStarted >= started
                    && p.epochStarted < started + seconds * 1000L)) continue;
            cohorts++;
            if (truncated.contains(entry.getKey())) { overflowCohorts++; continue; }
            if (events.stream().anyMatch(p -> p.stage.equals("WAIT_TIMEOUT"))) timedOut++;
            var phases = new LinkedHashMap<String, List<Point>>();
            for (String stage : CHAIN) {
                var observations = events.stream().filter(p -> p.stage.equals(stage) && !p.failed).distinct().toList();
                phases.put(stage, observations);
                if (!observations.isEmpty()) stages.merge(stage, 1L, Long::sum);
                else missing.merge(stage, 1L, Long::sum);
            }
            if (phases.values().stream().anyMatch(p -> p.size() > 1)) { ambiguous++; continue; }
            if (phases.values().stream().anyMatch(List::isEmpty)) continue;
            var init = phases.get("ITEM_INITIALIZED").getFirst();
            var claim = phases.get("CLAIMED").getFirst();
            var publish = phases.get("COMMAND_PUBLISHED").getFirst();
            var result = phases.get("RESULT_STORED").getFirst();
            var observed = phases.get("OBSERVED").getFirst();
            if (claim.from < init.to || publish.from < claim.to || result.from < publish.to || observed.from < result.to) {
                overlaps++; continue;
            }
            complete++;
            gaps.get("initializedEndToClaimStart").add(claim.from - init.to);
            gaps.get("publishEndToResultWriteStart").add(result.from - publish.to);
            gaps.get("resultWriteEndToObservedStart").add(observed.from - result.to);
        }
        var durations = new LinkedHashMap<String, Object>();
        gaps.forEach((name, values) -> durations.put(name, Map.of("samples", values.size(),
                "p50Millis", CallLoad.percentile(values, .5) / 1e6,
                "p95Millis", CallLoad.percentile(values, .95) / 1e6,
                "p99Millis", CallLoad.percentile(values, .99) / 1e6)));
        var result = new LinkedHashMap<String, Object>();
        result.put("sampleAlgorithm", "SHA-256(UTF-8(taskId + NUL + messageId)); low six bits of first byte zero (1/64)");
        result.put("retainedKeys", keys.size());
        result.put("measuredSubmissionKeys", cohorts);
        result.put("completeUnambiguousChains", complete);
        result.put("missingStages", missing);
        result.put("observedStageKeys", stages);
        result.put("retryOrRepeatedStageKeys", ambiguous);
        result.put("overlappingIntervalKeys", overlaps);
        result.put("timedOutWaiterKeys", timedOut);
        result.put("truncatedMeasuredKeys", overflowCohorts);
        result.put("duplicateEvents", duplicateEvents);
        result.put("keyOverflowEvents", keyOverflowEvents);
        result.put("perKeyOverflowEvents", eventOverflow);
        result.put("invalidEvents", invalid);
        result.put("complete", cohorts > 0 && complete == cohorts && keyOverflowEvents == 0 && eventOverflow == 0 && invalid == 0 && duplicateEvents == 0);
        result.put("intervals", durations);
        result.put("meaning", "Same Server JVM observation edges only, not Redis commit timestamps. Missing/retried/overlapping chains are counted and excluded from duration quantiles. End-to-end success and drain remain Harness evidence; stages after the HTTP timeout may still arrive.");
        return result;
    }
}
