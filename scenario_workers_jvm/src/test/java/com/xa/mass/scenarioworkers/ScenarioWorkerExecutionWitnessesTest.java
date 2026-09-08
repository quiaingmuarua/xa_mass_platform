package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.*;
import com.xa.mass.worker.error.WorkerException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScenarioWorkerExecutionWitnessesTest {
    @Test void actualReplicaClosuresRemainDistinctEvenWithTheSameToken() throws Exception {
        var journal = new ScenarioWorkerExecutionWitnesses();
        journal.definition("g", "first").handler().execute(Map.of("probeToken", "same", "delayMillis", 0L));
        journal.definition("g", "second").handler().execute(Map.of("probeToken", "same", "delayMillis", 0L));
        var first = journal.read(0, 2);
        assertThat(first.get("nextCursor")).isEqualTo(2L);
        assertThat(first.get("records").toString()).contains("first").doesNotContain("second");
        assertThat(journal.read(2, 2).get("records").toString()).contains("second").doesNotContain("first");
        assertThat(journal.read(4, 100).get("records")).isEqualTo(List.of());
        assertThatThrownBy(() -> journal.read(5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.read(0, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void handlerWaitingDoesNotHoldTheJournalGate() throws Exception {
        var journal = new ScenarioWorkerExecutionWitnesses();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> journal.definition("g", "w").handler()
                    .execute(Map.of("probeToken", "waiting", "delayMillis", 1_000L)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((long) journal.read(0, 100).get("nextCursor") == 0 && System.nanoTime() < end) Thread.sleep(5);
            assertThat(journal.read(0, 100).get("nextCursor")).isEqualTo(1L);
            assertThat(running).isNotDone();
            assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("null");
        }
    }

    @Test void overflowIsStickyAndNeverEvictsEvidence() throws Exception {
        var journal = new ScenarioWorkerExecutionWitnesses();
        var handler = journal.definition("g", "w").handler();
        for (int i = 0; i < ScenarioWorkerExecutionWitnesses.CAPACITY / 2; i++)
            handler.execute(Map.of("probeToken", "token", "delayMillis", 0L));
        assertThatThrownBy(() -> handler.execute(Map.of("probeToken", "overflow", "delayMillis", 0L)))
                .isInstanceOf(WorkerException.class);
        assertThat(journal.read(0, 1).get("overflowed")).isEqualTo(true);
        assertThat(journal.read(ScenarioWorkerExecutionWitnesses.CAPACITY - 1, 1).get("nextCursor"))
                .isEqualTo((long) ScenarioWorkerExecutionWitnesses.CAPACITY);
        assertThatThrownBy(() -> handler.execute(Map.of("probeToken", "invalid", "delayMillis", -1L)))
                .isInstanceOf(WorkerException.class);
    }
}
