package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskFaultStateTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsTwoWavesAndThenRecoveredThreeWaves() {
        Path path = temporaryDirectory.resolve("phase/task-fault.json");
        TaskFaultState initial = new TaskFaultState(
                "proof-1",
                Instant.parse("2026-09-01T00:00:00Z"),
                ConvergenceTestData.workerIds(),
                "target-coordinate",
                "worker-target",
                "backup-coordinate",
                "checkpoint-1",
                "checkpoint-message-1",
                ConvergenceTestData.batches(2),
                null
        );

        initial.save(path);
        assertThat(TaskFaultState.load(path)).isEqualTo(initial);

        TaskFaultState recovered = initial.recoveredBy(
                "worker-backup",
                ConvergenceTestData.batches(3)
        );
        recovered.save(path);
        assertThat(TaskFaultState.load(path)).isEqualTo(recovered);
    }

    @Test
    void rejectsUnknownStateShape() throws Exception {
        Path path = temporaryDirectory.resolve("invalid.json");
        Files.writeString(path, "{\"schemaVersion\":4}");

        assertThatThrownBy(() -> TaskFaultState.load(path))
                .isInstanceOf(IllegalStateException.class);
    }
}
