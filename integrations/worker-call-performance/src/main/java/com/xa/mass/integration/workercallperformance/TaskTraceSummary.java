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
        var excludedGaps = new LinkedHashMap<String, Map<String, Long>>();
        for (String gap : List.of("initializedEndToClaimStart", "publishEndToResultWriteStart", "resultWriteEndToObservedStart"))
        {
            gaps.put(gap, new ArrayList<>());
            excludedGaps.put(gap, new TreeMap<>());
        }
        var timeoutPositions = new TreeMap<String, Long>();
        long failedMarkerKeys = 0;
        long cohorts = 0, complete = 0, ambiguous = 0, overlaps = 0, timedOut = 0, overflowCohorts = 0;
        for (var entry : keys.entrySet()) {
            var events = entry.getValue();
            if (events.stream().noneMatch(p -> p.stage.equals("SUBMISSION") && p.epochStarted >= started
                    && p.epochStarted < started + seconds * 1000L)) continue;
            cohorts++;
            if (truncated.contains(entry.getKey())) { overflowCohorts++; continue; }
            if (events.stream().anyMatch(p -> p.stage.equals("WAIT_TIMEOUT"))) timedOut++;
            if (events.stream().anyMatch(p -> p.stage.equals("FAILED_RESULT_STORED"))) failedMarkerKeys++;
            var phases = new LinkedHashMap<String, List<Point>>();
            for (String stage : CHAIN) {
                var observations = events.stream().filter(p -> p.stage.equals(stage) && !p.failed).distinct().toList();
                phases.put(stage, observations);
                if (!observations.isEmpty()) stages.merge(stage, 1L, Long::sum);
                else missing.merge(stage, 1L, Long::sum);
            }
            addGap("initializedEndToClaimStart", phases.get("ITEM_INITIALIZED"), phases.get("CLAIMED"), gaps, excludedGaps);
            addGap("publishEndToResultWriteStart", phases.get("COMMAND_PUBLISHED"), phases.get("RESULT_STORED"), gaps, excludedGaps);
            addGap("resultWriteEndToObservedStart", phases.get("RESULT_STORED"), phases.get("OBSERVED"), gaps, excludedGaps);
            var timeouts = events.stream().filter(p -> p.stage.equals("WAIT_TIMEOUT")).distinct().toList();
            if (!timeouts.isEmpty()) {
                String position = "incomplete_or_ambiguous";
                if (timeouts.size() == 1) {
                    var timeout = timeouts.getFirst();
                    var claims = phases.get("CLAIMED");
                    var results = phases.get("RESULT_STORED");
                    if (claims.size() == 1 && timeout.to < claims.getFirst().from) position = "before_claim_started";
                    else if (results.size() == 1 && results.getFirst().to < timeout.from) position = "after_result_write_returned";
                    else if (claims.size() == 1 && claims.getFirst().to < timeout.from
                            && results.size() == 1 && timeout.to < results.getFirst().from) position = "after_claim_before_result_write";
                }
                timeoutPositions.merge(position, 1L, Long::sum);
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
        }
        var durations = new LinkedHashMap<String, Object>();
        gaps.forEach((name, values) -> durations.put(name, Map.of("samples", values.size(),
                "p50Millis", CallLoad.percentile(values, .5) / 1e6,
                "p95Millis", CallLoad.percentile(values, .95) / 1e6,
                "p99Millis", CallLoad.percentile(values, .99) / 1e6,
                "excludedKeys", excludedGaps.get(name))));
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
        result.put("timeoutPositions", timeoutPositions);
        result.put("failedResultMarkerKeys", failedMarkerKeys);
        result.put("truncatedMeasuredKeys", overflowCohorts);
        result.put("duplicateEvents", duplicateEvents);
        result.put("keyOverflowEvents", keyOverflowEvents);
        result.put("perKeyOverflowEvents", eventOverflow);
        result.put("invalidEvents", invalid);
        result.put("complete", cohorts > 0 && complete == cohorts && keyOverflowEvents == 0 && eventOverflow == 0 && invalid == 0 && duplicateEvents == 0);
        result.put("intervals", durations);
        result.put("meaning", "Same Server JVM observation edges only, not Redis commit timestamps. Each interval uses its own two unique nonoverlapping edges; missing/retried pairs are excluded explicitly. A missing HTTP observation does not discard earlier stage intervals. End-to-end success and drain remain Harness evidence; stages after the HTTP timeout may still arrive.");
        return result;
    }

    private static void addGap(String name, List<Point> before, List<Point> after,
                               Map<String, List<Long>> gaps, Map<String, Map<String, Long>> excluded) {
        String reason = before.isEmpty() || after.isEmpty() ? "missing" : before.size() != 1 || after.size() != 1 ? "repeated" :
                after.getFirst().from < before.getFirst().to ? "overlapping" : null;
        if (reason == null) gaps.get(name).add(after.getFirst().from - before.getFirst().to);
        else excluded.get(name).merge(reason, 1L, Long::sum);
    }
}
