package com.xa.mass.testing.confidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProofSummaryWorkflowGuardTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
                "proofClassDefinitions",
                "proofLineDefinitions",
                "proofClass",
                "proofLines",
                "proofQuestion",
                "evidenceShape",
                "gateType",
                "claimScope",
                "credentialRouteFamilies",
                "authorizedPositiveChecks",
                "authorizationExpectation",
                "authorized-positive",
                "wrongRejectionProofClass",
                "proofClassCounts",
                "proofLineCounts",
                "credentialCheckCount",
                "credentialCheckProofLineCounts",
                "authorizedPositiveCheckCount",
                "authorizedPositiveProofLineCounts",
                "Can It Be Used / Product API Capability Proof",
                "Can It Be Wrong / Policy & Safety Correctness Proof",
                "Can It Withstand This / Scoped Operational Resilience Proof",
                "Can it be used through supported external surfaces?",
                "Can it bind, authorize, schedule, or mutate incorrectly?",
                "Can it withstand this named load, fault, runtime, duration, and oracle?",
                "Product / API Capability Proof",
                "Scoped Operational Resilience Proof",
                "product-api-capability",
                "scoped-operational-resilience",
                "operator-admin-session",
                "task-producer-api-key",
                "worker-api-key",
                "scheduling-policy-correctness",
                "lifecycle-result-correctness",
                "authorization-no-bypass-safety",
                "scale-contention-evidence",
                "fault-recovery-evidence",
                "Capability proof must name the credential/session family",
                "valid credential/session on an allowed route/scope is not wrongly rejected",
                "Product/API Capability failure",
                "taskProducer.createAndAppendItems",
                "worker.submitResult",
                "engine deterministic tests (primary)",
                "representative server E2E",
                "primary deterministic scheduling/policy matrix",
                "representative real-wiring scheduling proof only",
                "Policy correctness is engine deterministic proof first",
                "Policy & Safety Correctness Proof",
                "policy-safety-correctness",
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
    void proofSummaryWriterEmitsStructuredProofClassesAndLinesFromFixtures(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Path surefireDir = tempDir.resolve("surefire-reports");
        Path platformDir = tempDir.resolve("platform-confidence");
        Path platformRunDir = platformDir.resolve("memory-local-fixture");
        Path startupDir = tempDir.resolve("server-default-startup");
        Path chaosDir = tempDir.resolve("chaos");
        Path perfDir = tempDir.resolve("perf");
        Path soakDir = tempDir.resolve("soak");
        Files.createDirectories(surefireDir);
        Files.createDirectories(platformRunDir);
        Files.createDirectories(startupDir);
        Files.createDirectories(chaosDir);
        Files.createDirectories(perfDir);
        Files.createDirectories(soakDir);

        Files.writeString(surefireDir.resolve("TEST-com.xa.mass.engine.EngineSchedulingCoreSuite.xml"),
                """
                        <testsuite name="com.xa.mass.engine.EngineSchedulingCoreSuite" tests="1" failures="0" errors="0" skipped="0">
                          <testcase classname="com.xa.mass.engine.EngineSchedulingCoreSuite" name="fixture"/>
                        </testsuite>
                        """,
                StandardCharsets.UTF_8);

        Files.writeString(platformRunDir.resolve("summary.json"),
                """
                        {
                          "status": "passed",
                          "profile": "memory-local",
                          "authMode": "session",
                          "operatorHeaderSupported": false,
                          "fixtureHeaderDisabled": true,
                          "sessionCookieSupported": true,
                          "adminRouteFamilies": ["/api/v1/auth", "/api/v1/control-plane", "/api/v1/api-keys", "/api/v1/tasks/{taskId}/commands"],
                          "sdkRouteFamilies": ["/api/v1/tasks", "/worker-api/v1"],
                          "credentialChecks": {
                            "unauthenticatedOperatorRoute": {
                              "status": "passed",
                              "httpStatus": 401,
                              "code": 401,
                              "failureReason": "Authentication is required"
                            },
                            "invalidTaskApiKey": {
                              "status": "passed",
                              "httpStatus": 401,
                              "code": 401,
                              "failureReason": "Invalid or missing API-key credential"
                            }
                          },
                          "adminAuthLoginLog": "logs/admin-auth-login.log",
                          "adminEnvLog": "logs/admin-env-init.log",
                          "adminTaskCommandLog": "logs/admin-task-command.log",
                          "workerLog": "logs/worker-launcher.log",
                          "taskLog": "logs/task-launcher.log",
                          "taskVerifyLog": "logs/task-result-verifier.log"
                        }
                        """,
                StandardCharsets.UTF_8);

        Path output = tempDir.resolve("summary.json");
        Process process = new ProcessBuilder(
                "node",
                SUMMARY_WRITER.toString(),
                "--job", "fixture",
                "--test-report-dir", surefireDir.toString(),
                "--platform-confidence-dir", platformDir.toString(),
                "--server-default-startup-dir", startupDir.toString(),
                "--chaos-dir", chaosDir.toString(),
                "--perf-dir", perfDir.toString(),
                "--soak-dir", soakDir.toString(),
                "--output", output.toString())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            process.destroyForcibly();
            fail("proof summary writer timed out; output=" + processOutput);
        }
        assertEquals(0, process.exitValue(), "proof summary writer failed: " + processOutput);

        Map<String, Object> summary = OBJECT_MAPPER.readValue(Files.readString(output, StandardCharsets.UTF_8),
                new TypeReference<>() {
                });
        assertTrue(list(summary.get("proofClassDefinitions")).stream()
                        .map(ProofSummaryWorkflowGuardTest::map)
                        .anyMatch(definition -> "product-api-capability".equals(definition.get("id"))),
                "summary must emit Product/API capability definition");
        assertTrue(list(summary.get("proofLineDefinitions")).stream()
                        .map(ProofSummaryWorkflowGuardTest::map)
                        .anyMatch(definition -> "authorization-no-bypass-safety".equals(definition.get("id"))),
                "summary must emit authorization/no-bypass proof line");

        Map<?, ?> totals = map(summary.get("totals"));
        assertEquals(7, totals.get("authorizedPositiveCheckCount"));
        assertEquals(2, totals.get("credentialCheckCount"));

        Map<?, ?> platformEvidence = evidenceByType(summary, "platform-confidence");
        assertEquals("product-api-capability", platformEvidence.get("proofClass"));
        assertTrue(list(platformEvidence.get("proofLines")).containsAll(List.of(
                "operator-admin-session",
                "task-producer-api-key",
                "worker-api-key")));

        Map<?, ?> taskProducerRoute = credentialRoute(platformEvidence, "task-api-key");
        assertEquals("authorized-positive", taskProducerRoute.get("authorizationExpectation"));
        assertEquals("product-api-capability", taskProducerRoute.get("wrongRejectionProofClass"));
        assertTrue(list(taskProducerRoute.get("routeFamilies")).contains("/api/v1/tasks"));

        Map<?, ?> taskReadCheck = authorizedPositiveCheck(platformEvidence, "taskProducer.readResult");
        assertEquals("task-producer-api-key", taskReadCheck.get("proofLine"));
        assertEquals("passed", taskReadCheck.get("status"));
        assertEquals("valid credential/session must not be wrongly rejected", taskReadCheck.get("claimScope"));

        Map<?, ?> workerResultCheck = authorizedPositiveCheck(platformEvidence, "worker.submitResult");
        assertEquals("worker-api-key", workerResultCheck.get("proofLine"));
        assertEquals("authorized-positive", workerResultCheck.get("authorizationExpectation"));

        Map<?, ?> credentialChecks = map(platformEvidence.get("credentialChecks"));
        Map<?, ?> invalidTaskKey = map(credentialChecks.get("invalidTaskApiKey"));
        assertEquals("authorization-no-bypass-safety", invalidTaskKey.get("proofLine"));
        assertEquals("representative credential-family fail-closed check", invalidTaskKey.get("claimScope"));

        Map<?, ?> engineEvidence = evidenceByType(summary, "surefire");
        assertEquals("policy-safety-correctness", engineEvidence.get("proofClass"));
        assertTrue(list(engineEvidence.get("proofLines")).contains("scheduling-policy-correctness"));
        assertEquals("primary deterministic scheduling/policy matrix", engineEvidence.get("claimScope"));
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

    private static Map<?, ?> evidenceByType(Map<String, Object> summary, String type) {
        return list(summary.get("evidence")).stream()
                .map(ProofSummaryWorkflowGuardTest::map)
                .filter(item -> type.equals(item.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing evidence type " + type));
    }

    private static Map<?, ?> credentialRoute(Map<?, ?> evidence, String credentialFamily) {
        return list(evidence.get("credentialRouteFamilies")).stream()
                .map(ProofSummaryWorkflowGuardTest::map)
                .filter(item -> credentialFamily.equals(item.get("credentialFamily")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing credential route family " + credentialFamily));
    }

    private static Map<?, ?> authorizedPositiveCheck(Map<?, ?> evidence, String operation) {
        return list(evidence.get("authorizedPositiveChecks")).stream()
                .map(ProofSummaryWorkflowGuardTest::map)
                .filter(item -> operation.equals(item.get("operation")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing authorized positive check " + operation));
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, "expected JSON object but got " + value);
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value) {
        assertNotNull(value, "expected JSON array but got null");
        assertTrue(value instanceof List<?>, "expected JSON array but got " + value);
        return (List<?>) value;
    }
}
