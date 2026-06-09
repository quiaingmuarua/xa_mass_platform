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
                "evidenceRole",
                "deterministic-proof",
                "runtime-proof",
                "source-guard",
                "schema-guard",
                "release-policy-guard",
                "artifact-metadata",
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
                "evidenceRoleCounts",
                "guardCounts",
                "guardProofLineCounts",
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
                "server-default-startup-restart",
                "authorization-no-bypass-matrix-schema-guard",
                "same-sqlite packaged-process restart/idempotence smoke",
                "authMode",
                "operatorHeaderSupported",
                "fixtureHeaderDisabled",
                "portPrecheck",
                "adminRouteFamilies",
                "sdkRouteFamilies",
                "credentialChecks",
                "failureReason",
                "defaultProfileLogObserved",
                "redisNamespaceMode",
                "scenarioContract",
                "durationOrVolume",
                "Missing scenario contract fields",
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

        Files.writeString(surefireDir.resolve("TEST-com.xa.mass.testing.confidence.ProofRegistryClosureGuardTest.xml"),
                """
                        <testsuite name="com.xa.mass.testing.confidence.ProofRegistryClosureGuardTest" tests="1" failures="0" errors="0" skipped="0">
                          <testcase classname="com.xa.mass.testing.confidence.ProofRegistryClosureGuardTest" name="fixture"/>
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
                          "authorizedPositiveChecks": [
                            {
                              "operation": "operator.login",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/api/v1/auth"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "admin-cli",
                              "sourceArtifact": "logs/admin-auth-login.log"
                            },
                            {
                              "operation": "operator.envInit",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/api/v1/control-plane", "/api/v1/api-keys"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "admin-cli",
                              "sourceArtifact": "logs/admin-env-init.log"
                            },
                            {
                              "operation": "operator.taskApprove",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/api/v1/tasks/{taskId}/commands"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "admin-cli",
                              "sourceArtifact": "logs/admin-task-command.log"
                            },
                            {
                              "operation": "taskProducer.createAndAppendItems",
                              "proofLine": "task-producer-api-key",
                              "credentialFamily": "task-api-key",
                              "routeFamilies": ["/api/v1/tasks"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "scenario-task-launcher",
                              "sourceArtifact": "logs/task-launcher.log"
                            },
                            {
                              "operation": "taskProducer.readResult",
                              "proofLine": "task-producer-api-key",
                              "credentialFamily": "task-api-key",
                              "routeFamilies": ["/api/v1/tasks"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "scenario-result-verifier",
                              "sourceArtifact": "logs/task-result-verifier.log"
                            },
                            {
                              "operation": "worker.registerAndPoll",
                              "proofLine": "worker-api-key",
                              "credentialFamily": "worker-api-key",
                              "routeFamilies": ["/worker-api/v1"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "scenario-worker-launcher",
                              "sourceArtifact": "logs/worker-launcher.log"
                            },
                            {
                              "operation": "worker.submitResult",
                              "proofLine": "worker-api-key",
                              "credentialFamily": "worker-api-key",
                              "routeFamilies": ["/worker-api/v1"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "scenario-worker-launcher",
                              "sourceArtifact": "logs/worker-launcher.log"
                            }
                          ],
                          "credentialChecks": {
                            "unauthenticatedOperatorRoute": {
                              "matrixRowId": "unauthenticatedOperatorRoute",
                              "operation": "operator.catalogSyncWithoutSession",
                              "credentialFamily": "none",
                              "routeFamily": "/api/v1/control-plane",
                              "proofLine": "authorization-no-bypass-safety",
                              "claimScope": "representative missing-session fail-closed check",
                              "status": "passed",
                              "httpStatus": 401,
                              "expectedHttpStatus": 401,
                              "code": 401,
                              "expectedCode": 401,
                              "expectedReason": "Authentication is required",
                              "failureReason": "Authentication is required"
                            },
                            "invalidTaskApiKey": {
                              "matrixRowId": "invalidTaskApiKey",
                              "operation": "taskProducer.listTasksWithInvalidApiKey",
                              "credentialFamily": "task-api-key",
                              "routeFamily": "/api/v1/tasks",
                              "proofLine": "authorization-no-bypass-safety",
                              "claimScope": "representative task credential fail-closed check",
                              "status": "passed",
                              "httpStatus": 401,
                              "expectedHttpStatus": 401,
                              "code": 401,
                              "expectedCode": 401,
                              "expectedReason": "Invalid or missing API-key credential",
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

        Path startupRunDir = startupDir.resolve("default-fixture");
        Files.createDirectories(startupRunDir);
        Files.writeString(startupRunDir.resolve("summary.json"),
                """
                        {
                          "status": "passed",
                          "defaultProfile": "durable-local",
                          "defaultProfileLogObserved": true,
                          "workDir": "target/server-default-startup/default-fixture",
                          "sqlitePath": "data/xa-mass-sqlite/xa_mass.db",
                          "restartCount": 1,
                          "firstHealth": "logs/first-health.json",
                          "secondHealth": "logs/second-health.json",
                          "firstOperatorLogin": "logs/first-login.json",
                          "secondOperatorLogin": "logs/second-login.json",
                          "sameSqliteRestart": true,
                          "redisNamespaceMode": "ci-isolated",
                          "authorizedPositiveChecks": [
                            {
                              "operation": "server.health",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/actuator/health"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "curl",
                              "sourceArtifact": "logs/first-health.json"
                            },
                            {
                              "operation": "operator.login",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/api/v1/auth"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "admin-cli",
                              "sourceArtifact": "logs/first-login.json"
                            },
                            {
                              "operation": "operator.loginAfterRestart",
                              "proofLine": "operator-admin-session",
                              "credentialFamily": "operator-session",
                              "routeFamilies": ["/api/v1/auth"],
                              "authorizationExpectation": "authorized-positive",
                              "wrongRejectionProofClass": "product-api-capability",
                              "status": "passed",
                              "claimScope": "valid credential/session must not be wrongly rejected",
                              "sourceProcess": "admin-cli",
                              "sourceArtifact": "logs/second-login.json"
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8);

        Files.writeString(chaosDir.resolve("missing-contract.json"),
                """
                        {
                          "task": {
                            "status": "TERMINAL"
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        Files.writeString(perfDir.resolve("complete-workload-mix.json"),
                """
                        {
                          "scenarioId": "workload-mix-slow-bulk-interactive-isolation",
                          "transport": "embedded",
                          "runtimeBackend": "memory",
                          "workerProfile": "SLOW_BULK",
                          "faultShape": "slow-bulk-interactive-isolation",
                          "config": {
                            "scenarioId": "workload-mix-slow-bulk-interactive-isolation",
                            "awaitSeconds": 30,
                            "bulkMessages": 10
                          },
                          "observation": {
                            "interactiveDispatchedBeforeBulkTerminal": true,
                            "interactiveDispatchedWhileBulkTaskStillRunning": true,
                            "bulkTerminalReason": "ALL_MESSAGES_SUCCEEDED",
                            "interactiveTerminalReason": "ALL_MESSAGES_SUCCEEDED"
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        Files.writeString(soakDir.resolve("complete-polling-soak.json"),
                """
                        {
                          "scenarioId": "polling-soak-noisy-mixed-result",
                          "transport": "polling",
                          "runtimeBackend": "memory",
                          "workerProfile": "NOISY",
                          "faultShape": "noisy-mixed-result",
                          "config": {
                            "scenarioId": "polling-soak-noisy-mixed-result",
                            "durationSeconds": 20
                          },
                          "activeLeasesAtEnd": 0,
                          "runtimeWork": {
                            "expiredWorkItems": 0
                          },
                          "proof": {
                            "runtimeInvariants": {
                              "ok": true
                            },
                            "trace": {
                              "droppedCount": 0
                            }
                          }
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
        assertEquals(10, totals.get("authorizedPositiveCheckCount"));
        assertEquals(2, totals.get("credentialCheckCount"));
        Map<?, ?> roleCounts = map(totals.get("evidenceRoleCounts"));
        assertEquals(1, roleCounts.get("deterministic-proof"));
        assertEquals(5, roleCounts.get("runtime-proof"));
        assertEquals(1, roleCounts.get("source-guard"));
        assertEquals(1, roleCounts.get("artifact-metadata"));
        Map<?, ?> proofLineCounts = map(totals.get("proofLineCounts"));
        assertEquals(1, proofLineCounts.get("scheduling-policy-correctness"));
        assertEquals(1, proofLineCounts.get("fault-recovery-evidence"));
        assertEquals(2, proofLineCounts.get("scale-contention-evidence"));
        Map<?, ?> guardProofLineCounts = map(totals.get("guardProofLineCounts"));
        assertEquals(1, guardProofLineCounts.get("authorization-no-bypass-safety"));

        Map<?, ?> platformEvidence = evidenceByType(summary, "platform-confidence");
        assertEquals("product-api-capability", platformEvidence.get("proofClass"));
        assertEquals("runtime-proof", platformEvidence.get("evidenceRole"));
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
        assertEquals("scenario-result-verifier", taskReadCheck.get("sourceProcess"));
        assertEquals("logs/task-result-verifier.log", taskReadCheck.get("sourceArtifact"));

        Map<?, ?> workerResultCheck = authorizedPositiveCheck(platformEvidence, "worker.submitResult");
        assertEquals("worker-api-key", workerResultCheck.get("proofLine"));
        assertEquals("authorized-positive", workerResultCheck.get("authorizationExpectation"));

        Map<?, ?> credentialChecks = map(platformEvidence.get("credentialChecks"));
        Map<?, ?> invalidTaskKey = map(credentialChecks.get("invalidTaskApiKey"));
        assertEquals("invalidTaskApiKey", invalidTaskKey.get("matrixRowId"));
        assertEquals("taskProducer.listTasksWithInvalidApiKey", invalidTaskKey.get("operation"));
        assertEquals("authorization-no-bypass-safety", invalidTaskKey.get("proofLine"));
        assertEquals("representative task credential fail-closed check", invalidTaskKey.get("claimScope"));

        Map<?, ?> engineEvidence = evidenceBySuite(summary, "com.xa.mass.engine.EngineSchedulingCoreSuite");
        assertEquals("policy-safety-correctness", engineEvidence.get("proofClass"));
        assertEquals("deterministic-proof", engineEvidence.get("evidenceRole"));
        assertTrue(list(engineEvidence.get("proofLines")).contains("scheduling-policy-correctness"));
        assertEquals("primary deterministic scheduling/policy matrix", engineEvidence.get("claimScope"));

        Map<?, ?> guardEvidence = evidenceBySuite(summary, "com.xa.mass.testing.confidence.ProofRegistryClosureGuardTest");
        assertEquals("source-guard", guardEvidence.get("evidenceRole"));
        assertTrue(list(guardEvidence.get("proofLines")).contains("authorization-no-bypass-safety"));

        Map<?, ?> startupRestartEvidence = evidenceByType(summary, "server-default-startup-restart");
        assertEquals("scoped-operational-resilience", startupRestartEvidence.get("proofClass"));
        assertEquals("runtime-proof", startupRestartEvidence.get("evidenceRole"));
        assertTrue(list(startupRestartEvidence.get("proofLines")).contains("fault-recovery-evidence"));
        assertEquals(true, startupRestartEvidence.get("sameSqliteRestart"));

        Map<?, ?> downgradedChaos = evidenceByType(summary, "chaos-report");
        assertEquals("downgraded", downgradedChaos.get("status"));
        assertEquals("artifact-metadata", downgradedChaos.get("evidenceRole"));
        assertTrue(list(downgradedChaos.get("proofLines")).isEmpty());
        List<?> missingContractFields = list(map(downgradedChaos.get("scenarioContract")).get("missingFields"));
        assertTrue(missingContractFields.contains("scenarioId"));
        assertTrue(missingContractFields.contains("runtimeBackend"));
        assertTrue(missingContractFields.contains("transport"));
        assertTrue(missingContractFields.contains("faultOrLoadShape"));
        assertTrue(missingContractFields.contains("durationOrVolume"));
        assertTrue(list(downgradedChaos.get("knownNonProofBoundaries")).stream()
                        .anyMatch(value -> String.valueOf(value).contains("Missing scenario contract fields")),
                "downgraded artifact must name missing scenario contract fields");

        Map<?, ?> perfEvidence = evidenceByType(summary, "perf-report");
        assertEquals("passed", perfEvidence.get("status"));
        assertEquals("runtime-proof", perfEvidence.get("evidenceRole"));
        assertTrue(list(perfEvidence.get("proofLines")).contains("scale-contention-evidence"));
        assertEquals("memory", perfEvidence.get("runtimeBackend"));
        assertEquals("embedded", perfEvidence.get("transport"));
        assertEquals("slow-bulk-interactive-isolation", perfEvidence.get("faultShape"));
        assertEquals("complete", map(perfEvidence.get("scenarioContract")).get("status"));

        Map<?, ?> soakEvidence = evidenceByType(summary, "soak-report");
        assertEquals("passed", soakEvidence.get("status"));
        assertEquals("runtime-proof", soakEvidence.get("evidenceRole"));
        assertTrue(list(soakEvidence.get("proofLines")).contains("scale-contention-evidence"));
        assertEquals("polling", soakEvidence.get("transport"));
        assertEquals("noisy-mixed-result", soakEvidence.get("faultShape"));
        assertEquals("complete", map(soakEvidence.get("scenarioContract")).get("status"));
    }

    @Test
    void proofSummaryWriterDoesNotMixDefaultTargetsWhenInputsAreScoped(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Path surefireDir = tempDir.resolve("surefire-reports");
        Files.createDirectories(surefireDir);
        Files.writeString(surefireDir.resolve("TEST-com.xa.mass.engine.EngineSchedulingCoreSuite.xml"),
                """
                        <testsuite name="com.xa.mass.engine.EngineSchedulingCoreSuite" tests="1" failures="0" errors="0" skipped="0">
                          <testcase classname="com.xa.mass.engine.EngineSchedulingCoreSuite" name="fixture"/>
                        </testsuite>
                        """,
                StandardCharsets.UTF_8);

        Path staleDir = REPO_ROOT.resolve("xa-mass-testing/target/platform-confidence/stale-scope-fixture");
        Path staleSummary = staleDir.resolve("summary.json");
        Files.createDirectories(staleDir);
        Files.writeString(staleSummary,
                """
                        {
                          "status": "passed",
                          "profile": "stale",
                          "authMode": "session"
                        }
                        """,
                StandardCharsets.UTF_8);

        try {
            Path output = tempDir.resolve("summary.json");
            Process process = new ProcessBuilder(
                    "node",
                    SUMMARY_WRITER.toString(),
                    "--job", "scoped-fixture",
                    "--test-report-dir", surefireDir.toString(),
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
            assertTrue(list(summary.get("evidence")).stream()
                            .map(ProofSummaryWorkflowGuardTest::map)
                            .noneMatch(item -> "platform-confidence".equals(item.get("type"))),
                    "scoped test-report summary must not read default platform-confidence target artifacts");
        } finally {
            Files.deleteIfExists(staleSummary);
            Files.deleteIfExists(staleDir);
        }
    }

    @Test
    void proofSummaryWriterDoesNotPromoteDefaultStartupPortPrecheckBlocker(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Path startupDir = tempDir.resolve("server-default-startup");
        Path startupRunDir = startupDir.resolve("port-precheck-fixture");
        Files.createDirectories(startupRunDir);
        Files.writeString(startupRunDir.resolve("summary.json"),
                """
                        {
                          "status": "blocked",
                          "category": "port-precheck",
                          "message": "default startup port is already serving health",
                          "baseUrl": "http://127.0.0.1:8088",
                          "portPrecheck": "occupied",
                          "defaultProfile": "durable-local",
                          "defaultProfileLogObserved": false,
                          "restartCount": 0,
                          "firstHealth": "not-run",
                          "secondHealth": "not-run",
                          "firstOperatorLogin": "not-run",
                          "secondOperatorLogin": "not-run",
                          "sameSqliteRestart": false,
                          "redisNamespaceMode": "default",
                          "logFailureScan": "not-run",
                          "authorizedPositiveChecks": []
                        }
                        """,
                StandardCharsets.UTF_8);

        Path output = tempDir.resolve("summary.json");
        Process process = new ProcessBuilder(
                "node",
                SUMMARY_WRITER.toString(),
                "--job", "default-startup-port-precheck-fixture",
                "--server-default-startup-dir", startupDir.toString(),
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
        Map<?, ?> startupEvidence = evidenceByType(summary, "server-default-startup");
        assertEquals("blocked", startupEvidence.get("status"));
        assertEquals("artifact-metadata", startupEvidence.get("evidenceRole"));
        assertEquals(null, startupEvidence.get("proofClass"));
        assertTrue(list(startupEvidence.get("proofLines")).isEmpty());
        assertEquals("occupied", startupEvidence.get("portPrecheck"));
        assertTrue(list(startupEvidence.get("knownNonProofBoundaries")).stream()
                        .anyMatch(value -> String.valueOf(value).contains("packaged process was not started")),
                "port precheck blocker must be a visible non-proof boundary");

        Map<?, ?> restartEvidence = evidenceByType(summary, "server-default-startup-restart");
        assertEquals("blocked", restartEvidence.get("status"));
        assertEquals("artifact-metadata", restartEvidence.get("evidenceRole"));
        assertEquals(null, restartEvidence.get("proofClass"));
        assertTrue(list(restartEvidence.get("proofLines")).isEmpty());

        Map<?, ?> totals = map(summary.get("totals"));
        assertEquals(2, map(totals.get("evidenceRoleCounts")).get("artifact-metadata"));
        assertEquals(0, totals.get("authorizedPositiveCheckCount"));
        assertTrue(!map(totals.get("proofClassCounts")).containsKey("product-api-capability"));
        assertTrue(!map(totals.get("proofLineCounts")).containsKey("operator-admin-session"));
        assertTrue(!map(totals.get("proofLineCounts")).containsKey("fault-recovery-evidence"));
        assertTrue(!map(totals.get("authorizedPositiveProofLineCounts")).containsKey("operator-admin-session"));
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
            if (".github/workflows/maven.yml".equals(workflow)) {
                assertTrue(source.contains("AuthorizationNoBypassMatrixGuardTest"),
                        workflow + " must run the no-bypass matrix guard");
            }
        }
    }

    private static Map<?, ?> evidenceByType(Map<String, Object> summary, String type) {
        return list(summary.get("evidence")).stream()
                .map(ProofSummaryWorkflowGuardTest::map)
                .filter(item -> type.equals(item.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing evidence type " + type));
    }

    private static Map<?, ?> evidenceBySuite(Map<String, Object> summary, String suite) {
        return list(summary.get("evidence")).stream()
                .map(ProofSummaryWorkflowGuardTest::map)
                .filter(item -> suite.equals(item.get("suite")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing evidence suite " + suite));
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
