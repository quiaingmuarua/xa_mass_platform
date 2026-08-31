package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConvergenceEvidenceTest {

    @TempDir
    Path directory;

    @Test
    void namesEvidenceByLaneAndAppendsAcrossPhases() throws Exception {
        Instant startedAt = Instant.parse("2026-08-31T00:00:00Z");
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                "proof-one",
                "task-fault-convergence",
                directory,
                startedAt
        );
        evidence.record("arm", "entered", Map.of("taskId", "task-one"));
        ConvergenceEvidence resumed = ConvergenceEvidence.resume(
                "proof-one",
                "task-fault-convergence",
                directory,
                startedAt
        );
        resumed.record("down", "disconnected", Map.of("workerId", "one"));
        resumed.writeSummary("succeeded", Map.of("resultCount", 1));

        Path timeline = directory.resolve(
                "worker-lab-task-fault-convergence-timeline.jsonl"
        );
        assertThat(Files.readAllLines(timeline)).hasSize(2);
        Map<String, Object> summary = Jsons.parseObject(Files.readString(
                directory.resolve(
                        "worker-lab-task-fault-convergence-summary.json"
                )
        ));
        assertThat(summary)
                .containsEntry("lane", "task-fault-convergence")
                .containsEntry("startedAt", startedAt.toString())
                .containsEntry("resultCount", 1L);
    }

    @Test
    void rejectsBusinessPayloadAndPropertiesFromEvidence() throws Exception {
        ConvergenceEvidence evidence = ConvergenceEvidence.begin(
                "proof-two",
                "state-convergence",
                directory
        );

        assertThatThrownBy(() -> evidence.record(
                "phase",
                "action",
                Map.of("workerProperties", Map.of())
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evidence.record(
                "phase",
                "action",
                Map.of("businessPayload", "secret")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
