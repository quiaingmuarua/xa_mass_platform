package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TriadProofEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndRestoresTheFixedTriadIdentityMap() throws Exception {
        Path evidencePath = temporaryDirectory.resolve("triad-baseline.json");
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=baseline",
                        "--proof-id=triad-proof",
                        "--evidence-file=" + evidencePath
                }
        );
        Map<String, String> identities = identities();
        TriadProofEvidence evidence = new TriadProofEvidence(
                options,
                AndroidWorkerTriadConvergenceHealth.SCENARIO,
                AndroidWorkerTriadConvergenceHealth.BASELINE_PHASE
        );
        evidence.workers(identities);
        evidence.check("connectedWorkerCount", 3);
        evidence.write();

        TriadProofEvidence.Baseline baseline =
                TriadProofEvidence.readBaseline(
                        evidencePath,
                        options,
                        AndroidWorkerTriadConvergenceHealth.SCENARIO,
                        AndroidWorkerTriadConvergenceHealth.BASELINE_PHASE
                );
        assertEquals(identities, baseline.workersByApplicationId());
        assertEquals(
                "worker-2",
                baseline.workerId(AndroidWorkerTriadTopology.OUTAGE_TARGET)
        );
    }

    @Test
    void rejectsMissingOrDuplicatedWorkerIdentities() {
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=baseline",
                        "--proof-id=triad-proof",
                        "--evidence-file="
                                + temporaryDirectory.resolve("evidence.json")
                }
        );
        TriadProofEvidence evidence = new TriadProofEvidence(
                options,
                AndroidWorkerTriadConvergenceHealth.SCENARIO,
                AndroidWorkerTriadConvergenceHealth.BASELINE_PHASE
        );
        assertThrows(IllegalArgumentException.class, () ->
                evidence.workers(Map.of(
                        AndroidWorkerTriadTopology.WORKERS.get(0).applicationId(),
                        "worker-1"
                ))
        );
        Map<String, String> duplicated = new LinkedHashMap<>(identities());
        duplicated.put(
                AndroidWorkerTriadTopology.WORKERS.get(2).applicationId(),
                "worker-2"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence.workers(duplicated)
        );
    }

    private static Map<String, String> identities() {
        Map<String, String> identities = new LinkedHashMap<>();
        for (int index = 0;
                index < AndroidWorkerTriadTopology.WORKERS.size();
                index++) {
            identities.put(
                    AndroidWorkerTriadTopology.WORKERS.get(index).applicationId(),
                    "worker-" + (index + 1)
            );
        }
        return Map.copyOf(identities);
    }
}
