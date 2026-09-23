package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskTraceSummaryTest {
    private static String key(int i) { return "%064x".formatted(i); }
    private static TaskTraceSummary complete() {
        var trace = new TaskTraceSummary();
        trace.add(key(1), new TaskTraceSummary.Point("SUBMISSION", 1, 9, 1000, false));
        long now = 10;
        for (String stage : List.of("ITEM_INITIALIZED", "CLAIMED", "COMMAND_PUBLISHED", "RESULT_STORED", "OBSERVED")) {
            trace.add(key(1), new TaskTraceSummary.Point(stage, now, now + 2, 1000, false));
            now += 10;
        }
        return trace;
    }
    @Test void joinsOnlySameKeyAndRetainsLateResultEdgesWithTheSubmissionCohort() {
        var trace = complete();
        var result = trace.summarize(1000, 120);
        assertThat(result).containsEntry("complete", true).containsEntry("completeUnambiguousChains", 1L);
        assertThat(trace.summarize(2000, 120)).containsEntry("measuredSubmissionKeys", 0L);
        trace.add(key(2), new TaskTraceSummary.Point("SUBMISSION", 1, 2, 1000, false));
        assertThat(trace.summarize(1000, 120)).containsEntry("complete", false).containsEntry("measuredSubmissionKeys", 2L);
    }
    @Test void repeatsFailuresAndOverlappingEdgesAreNotInventedLatencySamples() {
        var trace = complete();
        var retry = new TaskTraceSummary.Point("CLAIMED", 60, 62, 1000, false);
        trace.add(key(1), retry);
        trace.add(key(1), retry);
        assertThat(trace.summarize(1000, 120)).containsEntry("retryOrRepeatedStageKeys", 1L)
                .containsEntry("duplicateEvents", 1L).containsEntry("completeUnambiguousChains", 0L);
        var overlap = new TaskTraceSummary();
        for (String stage : List.of("SUBMISSION", "ITEM_INITIALIZED", "CLAIMED", "COMMAND_PUBLISHED", "RESULT_STORED", "OBSERVED"))
            overlap.add(key(1), new TaskTraceSummary.Point(stage, 1, 9, 1000, false));
        assertThat(overlap.summarize(1000, 120)).containsEntry("overlappingIntervalKeys", 1L).containsEntry("complete", false);
    }
    @Test void timeoutDoesNotDiscardItsLaterDispatchAndResultIntervals() {
        var trace = new TaskTraceSummary();
        trace.add(key(1), new TaskTraceSummary.Point("SUBMISSION", 1, 9, 1000, false));
        trace.add(key(1), new TaskTraceSummary.Point("ITEM_INITIALIZED", 2, 3, 1000, false));
        trace.add(key(1), new TaskTraceSummary.Point("WAIT_TIMEOUT", 20, 21, 1000, false));
        trace.add(key(1), new TaskTraceSummary.Point("CLAIMED", 30, 31, 130000, false));
        trace.add(key(1), new TaskTraceSummary.Point("COMMAND_PUBLISHED", 32, 33, 130000, false));
        trace.add(key(1), new TaskTraceSummary.Point("RESULT_STORED", 40, 41, 130000, false));
        var summary = trace.summarize(1000, 120);
        assertThat(summary).containsEntry("completeUnambiguousChains", 0L).containsEntry("complete", false);
        assertThat(CallApi.object(summary.get("timeoutPositions"))).containsEntry("before_claim_started", 1L);
        var intervals = CallApi.object(summary.get("intervals"));
        assertThat(CallApi.object(intervals.get("initializedEndToClaimStart"))).containsEntry("samples", 1);
        assertThat(CallApi.object(intervals.get("publishEndToResultWriteStart"))).containsEntry("samples", 1);
        assertThat(CallApi.object(intervals.get("resultWriteEndToObservedStart"))).containsEntry("samples", 0);
    }
    @Test void keyAndPerKeyBoundsPreserveOverflowAndNeverExportUnsafeIdentity() {
        var trace = complete();
        for (int i = 0; i < 100; i++) trace.add(key(1), new TaskTraceSummary.Point("CANDIDATES", i, i + 1, 1000, false));
        for (int i = 2; i < 10_003; i++) trace.add(key(i), new TaskTraceSummary.Point("SUBMISSION", 1, 2, 1000, false));
        trace.add("private-worker-id", new TaskTraceSummary.Point("SUBMISSION", 1, 2, 1000, false));
        var value = trace.summarize(1000, 120);
        assertThat(value).containsEntry("retainedKeys", 10_000).containsEntry("keyOverflowEvents", 2L)
                .containsEntry("perKeyOverflowEvents", 42L).containsEntry("invalidEvents", 1L).containsEntry("complete", false);
        assertThat(com.xa.mass.workerdelivery.json.Jsons.toJson(value)).doesNotContain("private-worker-id", key(1));
    }
}
