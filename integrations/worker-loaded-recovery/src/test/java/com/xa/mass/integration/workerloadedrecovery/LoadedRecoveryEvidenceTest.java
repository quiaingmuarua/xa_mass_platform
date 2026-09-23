package com.xa.mass.integration.workerloadedrecovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedRecoveryEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void privateIdentityBaselineRoundTripsWithoutEnteringSummaryEvidence() {
        Map<String, String> workerIds = Map.of(
                "workers-000.jsonl:1", "worker-a",
                "workers-001.jsonl:1", "worker-b"
        );
        Path baseline = temporaryDirectory.resolve("private/worker-ids.json");

        LoadedRecoveryEvidence.writeBaseline(baseline, "group-a", workerIds);

        assertThat(LoadedRecoveryEvidence.readBaseline(baseline, "group-a"))
                .containsExactlyInAnyOrderEntriesOf(workerIds);
        assertThat(LoadedRecoveryEvidence.identityDigest(workerIds.values()))
                .hasSize(64)
                .isEqualTo(LoadedRecoveryEvidence.identityDigest(List.of(
                        "worker-b",
                        "worker-a"
                )));
    }

    @Test
    void privateTopologySeparatesRetainedAndStoppedCoordinates() {
        Path topology = temporaryDirectory.resolve("private/topology.json");
        LoadedRecoveryEvidence.writeSummary(topology, Map.of(
                "workerGroupId", "group-a",
                "retainedLabWorkerKeys", List.of("workers-000.jsonl:1"),
                "stoppedLabWorkerKeys", List.of("workers-001.jsonl:1")
        ));

        LoadedRecoveryEvidence.WorkerTopology result = LoadedRecoveryEvidence.readTopology(
                topology,
                "group-a"
        );

        assertThat(result.retainedLabWorkerKeys())
                .containsExactly("workers-000.jsonl:1");
        assertThat(result.stoppedLabWorkerKeys())
                .containsExactly("workers-001.jsonl:1");
    }
}
