package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProofEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndRestoresACompatibleBaseline() throws Exception {
        Path evidencePath = temporaryDirectory.resolve("initial.json");
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=initial",
                        "--proof-id=proof-1",
                        "--evidence-file=" + evidencePath
                }
        );
        ProofEvidence evidence = new ProofEvidence(
                options,
                AndroidWorkerCorrectness.SCENARIO,
                AndroidWorkerCorrectness.INITIAL_PHASE
        );
        evidence.workerId("worker-1");
        evidence.check("messageId", "message-1");
        evidence.write();

        ProofEvidence.Baseline baseline = ProofEvidence.readBaseline(
                evidencePath,
                options,
                AndroidWorkerCorrectness.SCENARIO,
                AndroidWorkerCorrectness.INITIAL_PHASE
        );
        assertEquals("worker-1", baseline.workerId());
        assertEquals("message-1", baseline.requiredCheck("messageId"));
    }
}
