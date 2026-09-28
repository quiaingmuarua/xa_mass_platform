package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LaneCaseTest {

    @Test
    void casesAreExactlyThreePathsTimesThreeRates() {
        assertThat(LaneCase.Spec.parse("task-targeted-1000")).isEqualTo(new LaneCase.Spec(LaneCase.Path.TASK_TARGETED, 1000));
        assertThat(LaneCase.Spec.parse("direct-2000").name()).isEqualTo("direct-2000");
        for (String unknown : List.of("any-500", "task-any-100", "direct", "task-any-500-1"))
            assertThatThrownBy(() -> LaneCase.Spec.parse(unknown)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatorLimitStopsOfferingAtTheTenSecondCheckpoint() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var stop = new AtomicReference<String>();
        var parked = new ArrayList<Runnable>();
        // Capacity 1 and an executor that never runs: every later arrival is not sent.
        var batch = CallLoad.schedule(10, 30, 1, "lane", parked::add,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED), clock::get, clock::set, stop);

        assertThat(stop.get()).isEqualTo("generator-limited");
        assertThat(batch.samples()).hasSize(100);
        assertThat(batch.windowNanos()).isEqualTo(10_000_000_000L);
        assertThat(batch.summary()).containsEntry("generatorLimited", true);
    }

    @Test
    void protocolErrorStopsOfferingImmediately() {
        var clock = new AtomicLong(1_000_000_000L);
        var stop = new AtomicReference<String>();
        var batch = CallLoad.schedule(10, 30, 4, "lane", Runnable::run,
                (id, index) -> { throw new CallLoad.ProtocolFailure("bad shape"); }, clock::get, clock::set, stop);

        assertThat(stop.get()).isEqualTo("protocol-error");
        assertThat(batch.samples()).hasSize(1);
    }

    @Test
    void healthyLoadIsNotStoppedAndKeepsTheFullWindow() {
        var clock = new AtomicLong(1_000_000_000L);
        var stop = new AtomicReference<String>();
        var batch = CallLoad.schedule(10, 30, 4, "lane", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED), clock::get, clock::set, stop);

        assertThat(stop.get()).isNull();
        assertThat(batch.samples()).hasSize(300);
        assertThat(batch.windowNanos()).isEqualTo(30_000_000_000L);
    }

    @Test
    void callsAlternateGroupsAndRotateEachGroupsWorkers() {
        var world = Map.of("perf-a", List.of("a0", "a1"), "perf-b", List.of("b0", "b1"));
        var seen = new ArrayList<String>();
        for (int index = 0; index < 6; index++) {
            var target = LaneCase.target(index, world);
            seen.add(target.group() + ":" + target.worker());
        }

        assertThat(seen).containsExactly("perf-a:a0", "perf-b:b0", "perf-a:a1", "perf-b:b1", "perf-a:a0", "perf-b:b0");
        assertThat(LaneCase.groupOf(new CallLoad.Sample("prefix-measured-7", 0))).isEqualTo("perf-b");
        assertThat(LaneCase.groupOf(new CallLoad.Sample("prefix-measured-12", 0))).isEqualTo("perf-a");
    }

    @Test
    void workersAreSufficientOnlyWhenIdleHotStaysAtLeastTwiceTheExecutingCount() {
        var healthy = Map.<String, Object>of("offsetMillis", 0,
                "perf-a", Map.of("hot-score-overdue", 900L, "held-hot", 100L),
                "perf-b", Map.of("hot-score-overdue", 700L, "held-hot", 300L));
        var saturated = Map.<String, Object>of("offsetMillis", 5000,
                "perf-a", Map.of("hot-score-overdue", 900L, "held-hot", 100L),
                "perf-b", Map.of("hot-score-overdue", 500L, "held-hot", 500L));
        var unavailable = Map.<String, Object>of("offsetMillis", 10_000, "unavailable", true);

        assertThat(LaneCase.workersSufficient(List.of(healthy, unavailable))).isTrue();
        assertThat(LaneCase.workersSufficient(List.of(healthy, saturated))).isFalse();
        assertThat(LaneCase.workersSufficient(List.of(unavailable))).isFalse();
    }

    @Test
    void completionThroughputCountsSucceededResultsPerOfferedSecond() {
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(10, 2, 4, "lane", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, index % 2 == 0 ? CallLoad.Outcome.SUCCEEDED : CallLoad.Outcome.NOT_OBSERVED),
                clock::get, clock::set, new AtomicReference<>());
        assertThat(LaneCase.completedPerSecond(batch)).isEqualTo(5.0);
    }
}
