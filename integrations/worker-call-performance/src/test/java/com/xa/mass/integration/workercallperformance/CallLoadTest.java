package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CallLoadTest {
    @Test void fixedWindowsSeparatePlannedCohortsFromActualResponses() {
        long start = 1_000_000_000L;
        var early = new CallLoad.Sample("early", start + 29_999_000_000L);
        early.sent = early.planned + 110_000_000L;
        early.ended = start + 30_200_000_000L;
        early.httpStatus = 200;
        early.outcome = CallLoad.Outcome.SUCCEEDED;
        var boundary = new CallLoad.Sample("boundary", start + 30_000_000_000L);
        boundary.sent = boundary.planned;
        boundary.ended = boundary.planned + 1_000_000L;
        boundary.httpStatus = 200;
        boundary.outcome = CallLoad.Outcome.SUCCEEDED;
        var tail = new CallLoad.Sample("tail", start + 119_999_000_000L);
        tail.sent = tail.planned;
        tail.ended = start + 120_001_000_000L;
        tail.outcome = CallLoad.Outcome.UNKNOWN;
        var batch = new CallLoad.Batch(List.of(early, boundary, tail), start, 120_000_000_000L, new java.util.concurrent.CountDownLatch(0));
        var surge = DirectCallPerformance.summarize(batch, 0, 30);
        var sustained = DirectCallPerformance.summarize(batch, 30, 120);
        assertThat(surge).containsEntry("planned", 1).containsEntry("httpResponsesDuringWindow", 0L).containsEntry("generatorLimited", true);
        assertThat(sustained).containsEntry("planned", 2).containsEntry("httpResponsesDuringWindow", 2L)
                .containsEntry("generatorLimited", false).containsEntry("successRate", .5).containsEntry("outstandingHttpAtWindowEnd", 1L);
        assertThat(batch.responseSummary()).containsEntry("planned", 3).containsEntry("generatorLimited", true);
        assertThatThrownBy(() -> batch.responseSummary(0, 121_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void continuousTwoThousandRateKeepsOneScheduleThroughTheThirtySecondBoundary() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(2_000, 120, 4_096, "continuous", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED), clock::get, clock::set);
        batch.await();
        assertThat(batch.samples()).hasSize(240_000);
        assertThat(batch.samples().get(60_000).planned).isEqualTo(batch.started() + 30_000_000_000L);
        assertThat(batch.samples().getLast().planned).isEqualTo(batch.started() + 119_999_500_000L);
        assertThat(batch.cohort(0, 30_000_000_000L)).hasSize(60_000);
        assertThat(batch.cohort(30_000_000_000L, 120_000_000_000L)).hasSize(180_000);
        assertThat(clock.get()).isEqualTo(batch.started() + 120_000_000_000L);
    }

    @Test void scheduledArrivalTimesAndCountsIncludeUnknownAndUnobserved() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(5, 1, 5, "call", Runnable::run, (id, index) -> {
            clock.addAndGet(10_000_000);
            return switch (index) {
                case 0 -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED);
                case 1 -> new CallLoad.Reply(200, CallLoad.Outcome.FAILED);
                case 2 -> new CallLoad.Reply(200, CallLoad.Outcome.NOT_OBSERVED);
                case 3 -> throw new IOException("Unknown effect after send");
                default -> throw new CallLoad.ProtocolFailure("Wrong message ID");
            };
        }, clock::get, clock::set);
        batch.await();
        assertThat(batch.samples()).extracting(s -> s.planned).containsExactly(
                1_000_000_000L, 1_200_000_000L, 1_400_000_000L, 1_600_000_000L, 1_800_000_000L);
        assertThat(batch.summary()).containsEntry("planned", 5).containsEntry("sent", 5L)
                .containsEntry("accepted", 4L).containsEntry("successRate", .2);
        assertThat(CallApi.object(batch.summary().get("outcomes")).values().stream().mapToLong(n -> ((Number) n).longValue()).sum()).isEqualTo(5);
        assertThat(batch.samples().get(2).observed).isEqualTo("not_observed");
        assertThatThrownBy(() -> WorkerCallPerformanceMain.requireHealthy(batch)).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test void capacitySaturationDoesNotDelayOrRetryOfferedRequests() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var queued = new ArrayList<Runnable>();
        var batch = CallLoad.schedule(4, 1, 1, "bounded", queued::add,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED), clock::get, clock::set);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        batch.await();
        assertThat(batch.summary()).containsEntry("planned", 4).containsEntry("sent", 1L).containsEntry("generatorLimited", true);
        assertThat(batch.samples().stream().filter(s -> s.outcome == CallLoad.Outcome.NOT_SENT).count()).isEqualTo(3);
        assertThat(batch.samples().getFirst().sent - batch.samples().getFirst().planned).isEqualTo(1_000_000_000L);
    }

    @Test void nearestRankAndEmptySamplesAreExplicit() {
        var values = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(CallLoad.percentile(values, .50)).isEqualTo(50);
        assertThat(CallLoad.percentile(values, .95)).isEqualTo(95);
        assertThat(CallLoad.percentile(values, .99)).isEqualTo(99);
        assertThat(CallLoad.percentile(List.of(), .99)).isZero();
        assertThat(CallLoad.percentile(List.of(123L), .99)).isEqualTo(123);
    }

    @Test void anAcceptedUnobservedItemFailsClosureWithoutBecomingFailed() {
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(1, 1, 1, "missing", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.NOT_OBSERVED), clock::get, clock::set);
        assertThatThrownBy(() -> WorkerCallPerformanceMain.requireHealthy(batch)).hasMessageContaining("unobserved");
        assertThat(batch.samples().getFirst().observed).isEqualTo("not_observed");
        assertThat(batch.summary().get("unresolvedAcceptedIds")).isEqualTo(List.of("missing-0"));
    }
}
