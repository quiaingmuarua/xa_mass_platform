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
    Path directory;

    @Test
    void persistsStrictPhaseStateAndRecoveryIdentity() throws Exception {
        Path path = directory.resolve("phase.json");
        TaskFaultState initial = new TaskFaultState(
                "proof-one",
                Instant.parse("2026-08-31T00:00:00Z"),
                "worker-target",
                "task-one",
                "message-one",
                "token-one",
                1,
                null
        );
        initial.save(path);

        assertThat(TaskFaultState.load(path)).isEqualTo(initial);
        initial.recoveredBy("worker-backup").save(path);
        assertThat(TaskFaultState.load(path).recoveredWorkerId())
                .isEqualTo("worker-backup");
    }

    @Test
    void rejectsUnknownPhaseStateFields() throws Exception {
        Path path = directory.resolve("phase.json");
        Files.writeString(path, "{\"schemaVersion\":1,\"extra\":true}");

        assertThatThrownBy(() -> TaskFaultState.load(path))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phase state is invalid");
    }
}
