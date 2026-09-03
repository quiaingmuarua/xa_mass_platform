package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateConvergencePhaseStateTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSixWavesAndOneHundredWorkerIdentities() {
        Path path = temporaryDirectory.resolve("phase/state.json");
        StateConvergencePhaseState expected = new StateConvergencePhaseState(
                "proof-1",
                Instant.parse("2026-09-01T00:00:00Z"),
                ConvergenceTestData.workerIds(),
                ConvergenceTestData.batches(6),
                "property-task",
                "property-message"
        );

        expected.save(path);

        assertThat(StateConvergencePhaseState.load(path)).isEqualTo(expected);
    }

    @Test
    void rejectsIncompletePhaseState() throws Exception {
        Path path = temporaryDirectory.resolve("invalid.json");
        Files.writeString(path, "{\"schemaVersion\":3}");

        assertThatThrownBy(() -> StateConvergencePhaseState.load(path))
                .isInstanceOf(IllegalStateException.class);
    }
}
