package com.xa.mass.testing.workerfault;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerFaultScenarioIndexTest {

    @Test
    void mapsEveryCurrentPrChaosRunnerToAScenario() {
        Set<WorkerFaultScenarioIndex.RunnerFamily> mapped = WorkerFaultScenarioIndex.scenarios().stream()
                .filter(scenario -> scenario.proofLineOwner() == WorkerFaultScenarioIndex.ProofLineOwner.PR_CHAOS_SMOKE)
                .map(WorkerFaultScenarioIndex.Scenario::runnerFamily)
                .collect(Collectors.toSet());

        assertEquals(EnumSet.of(
                WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_ALL_MESSAGES_FAILED_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_MIXED_RESULTS_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_MESSAGE_RETRY_EXHAUSTED_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_WEBSOCKET_DISCONNECT_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_WEBSOCKET_LEASE_EXPIRY_REDISPATCH_CHAOS,
                WorkerFaultScenarioIndex.RunnerFamily.SDK_WEBSOCKET_LATE_RESULT_AFTER_LEASE_EXPIRY_CHAOS
        ), mapped);
    }

    @Test
    void exposesCurrentTraceAnalyzerMappings() {
        assertEquals("all-failed-terminal-convergence",
                WorkerFaultScenarioIndex.traceAnalyzerForChaosProfile("ALL_FAILED_TERMINAL_CONVERGENCE")
                        .orElseThrow()
                        .scenarioId());
        assertEquals("mixed-result-terminal-convergence",
                WorkerFaultScenarioIndex.traceAnalyzerForSoakTerminalReason("MIXED_MESSAGE_RESULTS")
                        .orElseThrow()
                        .scenarioId());
        assertTrue(WorkerFaultScenarioIndex.traceAnalyzerForSoakTerminalReason("ALL_MESSAGES_SUCCEEDED").isEmpty());
    }

    @Test
    void mapsCurrentNonChaosProofLines() {
        assertTrue(WorkerFaultScenarioIndex.scenarioForRunner(
                WorkerFaultScenarioIndex.RunnerFamily.TASK_WORKLOAD_MIX_SMOKE).isPresent());
        assertTrue(WorkerFaultScenarioIndex.scenarioForRunner(
                WorkerFaultScenarioIndex.RunnerFamily.TASK_INTERACTIVE_RETRY_WAKEUP_SMOKE).isPresent());
        assertTrue(WorkerFaultScenarioIndex.scenarioForRunner(
                WorkerFaultScenarioIndex.RunnerFamily.TASK_FLOW_LOAD_MODEL).isPresent());
        assertTrue(WorkerFaultScenarioIndex.scenarioForRunner(
                WorkerFaultScenarioIndex.RunnerFamily.SDK_TRANSPORT_LOAD).isPresent());
        assertTrue(WorkerFaultScenarioIndex.scenarioForRunner(
                WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_SCHEDULING_SOAK).isPresent());
    }

    @Test
    void resolvesScenarioIdToExecutableRunnerMainClass() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "polling-lease-expiry-redispatch").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS,
                scenario.runnerFamily());
        assertEquals("com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner",
                scenario.runnerFamily().mainClassName());
    }

    @Test
    void scenarioCliResolvesScenarioIdFromFirstArgument() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioCli.resolveScenario(
                new String[]{"polling-all-failed-terminal-convergence"});

        assertEquals(WorkerFaultScenarioIndex.Scenario.POLLING_ALL_FAILED_TERMINAL_CONVERGENCE, scenario);
    }

    @Test
    void scenarioCliExposesRunnerClassFromJavaLedger() {
        assertEquals("com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner",
                WorkerFaultScenarioCli.runnerClassName("polling-all-failed-terminal-convergence"));
    }

    @Test
    void scenarioCliListsLedgerRowsForDiscovery() {
        String scenarios = WorkerFaultScenarioCli.scenarioList();

        assertTrue(scenarios.contains(String.join("\t",
                "polling-lease-expiry-redispatch",
                "PR_CHAOS_SMOKE",
                "SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS",
                "polling",
                "memory",
                "STALL_LEASE_TAKEOVER",
                "lease-expiry-redispatch")));
        assertTrue(scenarios.contains(String.join("\t",
                "sdk-transport-load",
                "SDK_TRANSPORT_LOAD",
                "SDK_TRANSPORT_LOAD",
                "multi",
                "memory",
                "NORMAL",
                "delivery-diagnostics")));
    }
}
