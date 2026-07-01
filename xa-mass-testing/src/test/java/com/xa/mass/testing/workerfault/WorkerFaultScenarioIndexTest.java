package com.xa.mass.testing.workerfault;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void mapsSlowBulkInteractiveIsolationScenarioToWorkloadMixRunner() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "workload-mix-slow-bulk-interactive-isolation").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.ProofLineOwner.PERF_SMOKE, scenario.proofLineOwner());
        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.TASK_WORKLOAD_MIX_SMOKE, scenario.runnerFamily());
        assertEquals("embedded", scenario.transport());
        assertEquals("memory", scenario.runtimeBackend());
        assertEquals("SLOW_BULK", scenario.workerProfile());
        assertEquals("slow-bulk-interactive-isolation", scenario.faultShape());
    }

    @Test
    void mapsTransportLoadModeScenariosToTransportLoadRunner() {
        WorkerFaultScenarioIndex.Scenario polling = WorkerFaultScenarioIndex.scenarioForId(
                "sdk-transport-load-polling").orElseThrow();
        WorkerFaultScenarioIndex.Scenario websocket = WorkerFaultScenarioIndex.scenarioForId(
                "sdk-transport-load-websocket").orElseThrow();
        WorkerFaultScenarioIndex.Scenario socket = WorkerFaultScenarioIndex.scenarioForId(
                "sdk-transport-load-socket").orElseThrow();
        WorkerFaultScenarioIndex.Scenario websocketChurn = WorkerFaultScenarioIndex.scenarioForId(
                "sdk-transport-load-websocket-churn").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.SDK_TRANSPORT_LOAD, polling.runnerFamily());
        assertEquals("polling", polling.transport());
        assertEquals("websocket", websocket.transport());
        assertEquals("socket", socket.transport());
        assertEquals("delivery-diagnostics", polling.faultShape());
        assertEquals("delivery-diagnostics", websocket.faultShape());
        assertEquals("delivery-diagnostics", socket.faultShape());
        assertEquals("websocket", websocketChurn.transport());
        assertEquals("FLAKY_TRANSPORT", websocketChurn.workerProfile());
        assertEquals("transport-connection-churn", websocketChurn.faultShape());
    }

    @Test
    void mapsNoisyMixedResultSoakScenarioToPollingSoakRunner() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "polling-soak-noisy-mixed-result").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.ProofLineOwner.POLLING_SOAK, scenario.proofLineOwner());
        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_SCHEDULING_SOAK, scenario.runnerFamily());
        assertEquals("polling", scenario.transport());
        assertEquals("memory", scenario.runtimeBackend());
        assertEquals("NOISY", scenario.workerProfile());
        assertEquals("noisy-mixed-result", scenario.faultShape());
    }

    @Test
    void mapsRedisRestartRecoveryAsScheduledInfraChaos() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "polling-redis-restart-recovery").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.ProofLineOwner.SCHEDULED_INFRA_CHAOS,
                scenario.proofLineOwner());
        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_REDIS_RESTART_RECOVERY_CHAOS,
                scenario.runnerFamily());
        assertEquals("redis", scenario.runtimeBackend());
        assertEquals("redis-runtime-restart-recovery", scenario.faultShape());
        assertEquals("lease-expiry-redispatch", scenario.traceAnalyzers().getFirst().scenarioId());
    }

    @Test
    void redisRestartRecoveryScenarioIsRuntimeOwnerReconnectNotRedisServerFailure() throws IOException {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "polling-redis-restart-recovery").orElseThrow();
        String faultShape = scenario.faultShape();
        String runnerSource = Files.readString(repositoryRoot().resolve(
                "xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/SdkPollingRedisRestartRecoveryChaosRunner.java"),
                StandardCharsets.UTF_8);

        assertEquals("redis-runtime-restart-recovery", faultShape);
        assertTrue(runnerSource.contains("\"restartMode\", \"runtime-owner-reconnect\""),
                "Redis restart recovery runner must report runtime owner reconnect, not Redis process kill/failover");
        assertTrue(!faultShape.contains("process")
                        && !faultShape.contains("kill")
                        && !faultShape.contains("partition")
                        && !faultShape.contains("failover"),
                "Redis runtime owner reconnect scenario must not claim Redis process kill, partition, or failover proof");
    }

    @Test
    void mapsDroppedResultRetryAliasToPollingLeaseExpiryRunner() {
        WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(
                "fault.dropped-result-retry").orElseThrow();

        assertEquals(WorkerFaultScenarioIndex.ProofLineOwner.PR_CHAOS_SMOKE, scenario.proofLineOwner());
        assertEquals(WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS,
                scenario.runnerFamily());
        assertEquals("polling", scenario.transport());
        assertEquals("memory", scenario.runtimeBackend());
        assertEquals("STALL_LEASE_TAKEOVER", scenario.workerProfile());
        assertEquals("dropped-result-retry", scenario.faultShape());
        assertEquals("lease-expiry-redispatch", scenario.traceAnalyzers().getFirst().scenarioId());
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
                "fault.dropped-result-retry",
                "PR_CHAOS_SMOKE",
                "SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS",
                "polling",
                "memory",
                "STALL_LEASE_TAKEOVER",
                "dropped-result-retry")));
        assertTrue(scenarios.contains(String.join("\t",
                "sdk-transport-load",
                "SDK_TRANSPORT_LOAD",
                "SDK_TRANSPORT_LOAD",
                "multi",
                "memory",
                "NORMAL",
                "delivery-diagnostics")));
        assertTrue(scenarios.contains(String.join("\t",
                "workload-mix-slow-bulk-interactive-isolation",
                "PERF_SMOKE",
                "TASK_WORKLOAD_MIX_SMOKE",
                "embedded",
                "memory",
                "SLOW_BULK",
                "slow-bulk-interactive-isolation")));
        assertTrue(scenarios.contains(String.join("\t",
                "sdk-transport-load-polling",
                "SDK_TRANSPORT_LOAD",
                "SDK_TRANSPORT_LOAD",
                "polling",
                "memory",
                "NORMAL",
                "delivery-diagnostics")));
        assertTrue(scenarios.contains(String.join("\t",
                "sdk-transport-load-websocket-churn",
                "SDK_TRANSPORT_LOAD",
                "SDK_TRANSPORT_LOAD",
                "websocket",
                "memory",
                "FLAKY_TRANSPORT",
                "transport-connection-churn")));
        assertTrue(scenarios.contains(String.join("\t",
                "polling-redis-restart-recovery",
                "SCHEDULED_INFRA_CHAOS",
                "SDK_POLLING_REDIS_RESTART_RECOVERY_CHAOS",
                "polling",
                "redis",
                "STALL_RESTART_TAKEOVER",
                "redis-runtime-restart-recovery")));
        assertTrue(scenarios.contains(String.join("\t",
                "polling-soak-noisy-mixed-result",
                "POLLING_SOAK",
                "SDK_POLLING_SCHEDULING_SOAK",
                "polling",
                "memory",
                "NOISY",
                "noisy-mixed-result")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve(
                    "xa-mass-testing/src/main/java/com/xa/mass/testing/workerfault/WorkerFaultScenarioIndex.java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + System.getProperty("user.dir"));
    }
}
