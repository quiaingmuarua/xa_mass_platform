package com.xa.mass.testing.confidence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofSummaryWorkflowGuardTest {
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path SUMMARY_WRITER =
            REPO_ROOT.resolve("xa-mass-testing/scripts/write-proof-summary.mjs");

    @Test
    void proofSummaryWriterKeepsProofCredibilityFieldsAndBoundaries() throws IOException {
        String source = Files.readString(SUMMARY_WRITER, StandardCharsets.UTF_8);

        for (String requiredToken : List.of(
                "knownNonProofBoundaries",
                "criticalInvariantIds",
                "traceAnalyzerIds",
                "scheduledManualEvidence",
                "platform-confidence",
                "server-default-startup",
                "authMode",
                "operatorHeaderSupported",
                "fixtureHeaderDisabled",
                "adminRouteFamilies",
                "sdkRouteFamilies",
                "credentialChecks",
                "failureReason",
                "defaultProfileLogObserved",
                "redisNamespaceMode",
                "--test-report-dir",
                "--platform-confidence-dir",
                "--server-default-startup-dir",
                "--chaos-dir",
                "--perf-dir",
                "--soak-dir",
                "Redis process kill, partition/failover",
                "Frontend workflow success is frontend quality")) {
            assertTrue(source.contains(requiredToken),
                    "proof summary writer must keep token: " + requiredToken);
        }
    }

    @Test
    void proofWorkflowsUploadProofSummaryArtifacts() throws IOException {
        for (String workflow : List.of(
                ".github/workflows/maven.yml",
                ".github/workflows/platform-confidence.yml",
                ".github/workflows/external-worker-samples.yml",
                ".github/workflows/redis-runtime.yml",
                ".github/workflows/perf-smokes.yml",
                ".github/workflows/soak-smokes.yml")) {
            Path workflowPath = REPO_ROOT.resolve(workflow);
            String source = Files.readString(workflowPath, StandardCharsets.UTF_8);
            assertTrue(source.contains("write-proof-summary.mjs"),
                    workflow + " must write a proof summary before artifact upload");
            assertTrue(source.contains("--test-report-dir")
                            || source.contains("--platform-confidence-dir")
                            || source.contains("--server-default-startup-dir")
                            || source.contains("--chaos-dir")
                            || source.contains("--perf-dir")
                            || source.contains("--soak-dir"),
                    workflow + " must scope proof summary inputs to this job's report directories");
            assertTrue(source.contains("xa-mass-testing/target/proof-summary/**"),
                    workflow + " must upload proof summary artifacts");
        }
    }
}
