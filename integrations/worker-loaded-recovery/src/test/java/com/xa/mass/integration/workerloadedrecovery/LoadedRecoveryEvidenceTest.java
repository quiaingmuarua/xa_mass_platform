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

    @Test
    void notHotStateKeyKeepsSchedulingAndNetworkProjectionsWithoutIdentity() {
        assertThat(WorkerLoadedRecoveryMain.notHotStateKey("recovery", "connected"))
                .isEqualTo("recovery|connected");
        assertThat(WorkerLoadedRecoveryMain.notHotStateKey(null, null))
                .isEqualTo("absent|absent");
        assertThat(WorkerLoadedRecoveryMain.notHotStateKey("cold", "disconnected"))
                .isEqualTo("cold|disconnected");
    }

    @Test
    void idleConnectionGateAcceptsTheFailedNightlyRecoverySnapshot() {
        var observed = new WorkerLoadedRecoveryMain.Scan(
                10_000, 9_720, 9_720, 0, 0, 0, 0, 0,
                Map.of("recovery|connected", 280));
        assertThat(observed.connectionsStable(9_900)).isTrue();
        assertThat(observed.activeNotHotStates()).containsEntry("recovery|connected", 280);

        // Neither active nor stopped scheduling polarity is an idle liveness promise.
        var idle = new WorkerLoadedRecoveryMain.Scan(
                10_000, 0, 0, 0, 0, 0, 2, 0,
                Map.of("recovery|connected", 10_000));
        assertThat(idle.connectionsStable(9_900)).isTrue();
        assertThat(idle.stoppedHot()).isEqualTo(2);
    }

    @Test
    void idleConnectionGateStillRejectsLostConnectionsAndMissingIdentities() {
        for (var observed : List.of(
                new WorkerLoadedRecoveryMain.Scan(9_899, 9_899, 9_899, 0, 0, 0, 0, 0, Map.of()),
                new WorkerLoadedRecoveryMain.Scan(10_000, 9_720, 9_720, 0, 1, 0, 0, 0, Map.of()),
                new WorkerLoadedRecoveryMain.Scan(10_000, 9_720, 9_720, 0, 0, 1, 0, 0, Map.of()),
                new WorkerLoadedRecoveryMain.Scan(10_000, 9_720, 9_720, 0, 0, 0, 0, 1, Map.of())
        )) {
            assertThat(observed.connectionsStable(9_900)).isFalse();
        }
    }
}
