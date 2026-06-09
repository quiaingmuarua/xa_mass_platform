package com.xa.mass.testing.confidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfSoakReleaseEvidenceGuardTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path RELEASE_EVIDENCE =
            REPO_ROOT.resolve("xa-mass-testing/proof/perf-soak-release-evidence.json");
    private static final Path PERF_SCRIPT =
            REPO_ROOT.resolve("xa-mass-testing/scripts/run-perf-smokes.sh");
    private static final Path FAST_SOAK_SCRIPT =
            REPO_ROOT.resolve("xa-mass-testing/scripts/run-polling-scheduling-fast-soak.sh");
    private static final Path SUMMARY_WRITER =
            REPO_ROOT.resolve("xa-mass-testing/scripts/write-proof-summary.mjs");

    @Test
    void releaseEvidenceScenariosStayStableAndMappedToTheWorkerFaultLedger() throws IOException {
        Map<String, Object> evidence = readEvidence();
        Map<?, ?> workflowPolicy = map(evidence.get("workflowPolicy"));
        assertEquals(Boolean.FALSE, workflowPolicy.get("prGate"),
                "perf/soak release evidence must stay scheduled/manual until thresholds are calibrated");
        assertEquals("xa-mass-testing", workflowPolicy.get("owner"));
        assertEquals("not-pr-gate-until-promoted", workflowPolicy.get("gateEligibility"));
        assertNotNull(workflowPolicy.get("comparisonTarget"));
        assertNotNull(workflowPolicy.get("thresholdPolicy"));
        assertFalse(list(workflowPolicy.get("promotionCriteria")).isEmpty(),
                "release evidence policy must name promotion criteria before any PR-gate promotion");
        assertFalse(list(workflowPolicy.get("demotionTriggers")).isEmpty(),
                "release evidence policy must name demotion triggers");

        List<?> scenarios = list(evidence.get("stableScenarios"));
        assertEquals(Set.of(
                        "workload-mix-slow-bulk-interactive-isolation",
                        "interactive-retry-wakeup",
                        "polling-soak-noisy-mixed-result"),
                scenarioIds(scenarios));

        for (Object value : scenarios) {
            Map<?, ?> scenario = map(value);
            String scenarioId = String.valueOf(scenario.get("scenarioId"));
            WorkerFaultScenarioIndex.Scenario ledgerRow =
                    WorkerFaultScenarioIndex.scenarioForId(scenarioId).orElseThrow();
            assertTrue(ledgerRow.proofLineOwner() == WorkerFaultScenarioIndex.ProofLineOwner.PERF_SMOKE
                            || ledgerRow.proofLineOwner() == WorkerFaultScenarioIndex.ProofLineOwner.POLLING_SOAK,
                    scenarioId + " must be perf or soak release evidence");
            assertEquals("xa-mass-testing", scenario.get("owner"),
                    scenarioId + " must name the release-evidence owner");
            assertEquals("scheduled/manual-only", scenario.get("gateEligibility"),
                    scenarioId + " must not silently become a PR gate");
            assertFalse(list(scenario.get("thresholdSignals")).isEmpty(),
                    scenarioId + " must name hard threshold signals");
            assertFalse(list(scenario.get("trendSignals")).isEmpty(),
                    scenarioId + " must name trend signals");
        }
    }

    @Test
    void scriptsSelectStableReleaseEvidenceScenarioIds() throws IOException {
        String perfScript = Files.readString(PERF_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(perfScript.contains("MASS_WORKLOAD_SMOKE_SCENARIO_ID:-workload-mix-slow-bulk-interactive-isolation"),
                "perf smoke must default to the stable workload-mix release evidence row");
        assertTrue(perfScript.contains("TaskInteractiveRetryWakeupSmokeRunner"),
                "perf smoke must keep the interactive retry wakeup release evidence runner");

        String soakScript = Files.readString(FAST_SOAK_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(soakScript.contains("MASS_SOAK_SCENARIO_ID:-polling-soak-noisy-mixed-result"),
                "fast soak must default to the stable noisy mixed-result release evidence row");
    }

    @Test
    void proofSummaryConsumesReleaseEvidencePolicy() throws IOException {
        String writer = Files.readString(SUMMARY_WRITER, StandardCharsets.UTF_8);
        for (String token : List.of(
                "perf-soak-release-evidence.json",
                "releaseEvidencePolicy",
                "releaseEvidenceReports",
                "thresholdSignals",
                "trendSignals",
                "gateEligibility",
                "promotionCriteria",
                "demotionTriggers",
                "comparisonTarget")) {
            assertTrue(writer.contains(token), "proof summary writer must keep token: " + token);
        }
    }

    private static Map<String, Object> readEvidence() throws IOException {
        return OBJECT_MAPPER.readValue(Files.readString(RELEASE_EVIDENCE, StandardCharsets.UTF_8),
                new TypeReference<>() {
                });
    }

    private static Set<String> scenarioIds(List<?> scenarios) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : scenarios) {
            ids.add(String.valueOf(map(value).get("scenarioId")));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, "expected JSON object but got " + value);
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value) {
        assertTrue(value instanceof List<?>, "expected JSON array but got " + value);
        return (List<?>) value;
    }
}
