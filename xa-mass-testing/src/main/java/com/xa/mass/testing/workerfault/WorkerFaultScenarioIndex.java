package com.xa.mass.testing.workerfault;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class WorkerFaultScenarioIndex {

    private WorkerFaultScenarioIndex() {
    }

    public enum ProofLineOwner {
        PR_CHAOS_SMOKE,
        PERF_SMOKE,
        FULL_PERF_MODEL,
        SDK_TRANSPORT_LOAD,
        POLLING_SOAK
    }

    public enum RunnerFamily {
        SDK_POLLING_ALL_MESSAGES_FAILED_CHAOS,
        SDK_POLLING_MIXED_RESULTS_CHAOS,
        SDK_POLLING_MESSAGE_RETRY_EXHAUSTED_CHAOS,
        SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS,
        SDK_WEBSOCKET_DISCONNECT_CHAOS,
        SDK_WEBSOCKET_LEASE_EXPIRY_REDISPATCH_CHAOS,
        SDK_WEBSOCKET_LATE_RESULT_AFTER_LEASE_EXPIRY_CHAOS,
        TASK_WORKLOAD_MIX_SMOKE,
        TASK_INTERACTIVE_RETRY_WAKEUP_SMOKE,
        TASK_FLOW_LOAD_MODEL,
        SDK_TRANSPORT_LOAD,
        SDK_POLLING_SCHEDULING_SOAK
    }

    public enum TraceAnalyzerScenario {
        ALL_FAILED_TERMINAL_CONVERGENCE("all-failed-terminal-convergence"),
        MIXED_RESULT_TERMINAL_CONVERGENCE("mixed-result-terminal-convergence"),
        LEASE_EXPIRY_REDISPATCH("lease-expiry-redispatch"),
        LATE_STALE_RESULT_REPLAY("late-stale-result-replay"),
        LATE_WORKER_BACKFILL("late-worker-backfill");

        private final String scenarioId;

        TraceAnalyzerScenario(String scenarioId) {
            this.scenarioId = scenarioId;
        }

        public String scenarioId() {
            return scenarioId;
        }
    }

    public enum Scenario {
        POLLING_ALL_FAILED_TERMINAL_CONVERGENCE(
                "polling-all-failed-terminal-convergence",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_POLLING_ALL_MESSAGES_FAILED_CHAOS,
                "polling",
                "memory",
                "ALL_FAILED",
                "result-failure",
                List.of(TraceAnalyzerScenario.ALL_FAILED_TERMINAL_CONVERGENCE)
        ),
        POLLING_MIXED_RESULT_TERMINAL_CONVERGENCE(
                "polling-mixed-result-terminal-convergence",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_POLLING_MIXED_RESULTS_CHAOS,
                "polling",
                "memory",
                "MIXED_RESULT",
                "mixed-result",
                List.of(TraceAnalyzerScenario.MIXED_RESULT_TERMINAL_CONVERGENCE)
        ),
        POLLING_RETRY_EXHAUSTED(
                "polling-retry-exhausted",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_POLLING_MESSAGE_RETRY_EXHAUSTED_CHAOS,
                "polling",
                "memory",
                "ALL_FAILED",
                "retry-exhausted",
                List.of()
        ),
        POLLING_LEASE_EXPIRY_REDISPATCH(
                "polling-lease-expiry-redispatch",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_POLLING_LEASE_EXPIRY_REDISPATCH_CHAOS,
                "polling",
                "memory",
                "STALL_LEASE_TAKEOVER",
                "lease-expiry-redispatch",
                List.of(TraceAnalyzerScenario.LEASE_EXPIRY_REDISPATCH)
        ),
        WEBSOCKET_DISCONNECT_RECONNECT(
                "websocket-disconnect-reconnect",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_WEBSOCKET_DISCONNECT_CHAOS,
                "websocket",
                "memory",
                "DISCONNECT_CHURN",
                "transport-disconnect",
                List.of()
        ),
        WEBSOCKET_LEASE_EXPIRY_REDISPATCH(
                "websocket-lease-expiry-redispatch",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_WEBSOCKET_LEASE_EXPIRY_REDISPATCH_CHAOS,
                "websocket",
                "memory",
                "STALL_LEASE_TAKEOVER",
                "lease-expiry-redispatch",
                List.of(TraceAnalyzerScenario.LEASE_EXPIRY_REDISPATCH)
        ),
        WEBSOCKET_LATE_STALE_RESULT_REPLAY(
                "websocket-late-stale-result-replay",
                ProofLineOwner.PR_CHAOS_SMOKE,
                RunnerFamily.SDK_WEBSOCKET_LATE_RESULT_AFTER_LEASE_EXPIRY_CHAOS,
                "websocket",
                "memory",
                "LATE_STALE_RESULT",
                "late-stale-result",
                List.of(TraceAnalyzerScenario.LATE_STALE_RESULT_REPLAY)
        ),
        WORKLOAD_MIX_INTERACTIVE_UNDER_BULK(
                "workload-mix-interactive-under-bulk",
                ProofLineOwner.PERF_SMOKE,
                RunnerFamily.TASK_WORKLOAD_MIX_SMOKE,
                "embedded",
                "memory",
                "NORMAL",
                "lane-isolation",
                List.of()
        ),
        INTERACTIVE_RETRY_WAKEUP(
                "interactive-retry-wakeup",
                ProofLineOwner.PERF_SMOKE,
                RunnerFamily.TASK_INTERACTIVE_RETRY_WAKEUP_SMOKE,
                "embedded",
                "memory",
                "NORMAL",
                "retry-wakeup",
                List.of()
        ),
        TASK_FLOW_LOAD_MODEL(
                "task-flow-load-model",
                ProofLineOwner.FULL_PERF_MODEL,
                RunnerFamily.TASK_FLOW_LOAD_MODEL,
                "embedded",
                "memory",
                "NORMAL",
                "load-model",
                List.of()
        ),
        SDK_TRANSPORT_LOAD(
                "sdk-transport-load",
                ProofLineOwner.SDK_TRANSPORT_LOAD,
                RunnerFamily.SDK_TRANSPORT_LOAD,
                "multi",
                "memory",
                "NORMAL",
                "delivery-diagnostics",
                List.of()
        ),
        POLLING_SCHEDULING_SOAK(
                "polling-scheduling-soak",
                ProofLineOwner.POLLING_SOAK,
                RunnerFamily.SDK_POLLING_SCHEDULING_SOAK,
                "polling",
                "memory",
                "MIXED_RESULT",
                "scheduling-soak",
                List.of(
                        TraceAnalyzerScenario.LATE_WORKER_BACKFILL,
                        TraceAnalyzerScenario.ALL_FAILED_TERMINAL_CONVERGENCE,
                        TraceAnalyzerScenario.MIXED_RESULT_TERMINAL_CONVERGENCE
                )
        );

        private final String scenarioId;
        private final ProofLineOwner proofLineOwner;
        private final RunnerFamily runnerFamily;
        private final String transport;
        private final String runtimeBackend;
        private final String workerProfile;
        private final String faultShape;
        private final List<TraceAnalyzerScenario> traceAnalyzers;

        Scenario(String scenarioId,
                 ProofLineOwner proofLineOwner,
                 RunnerFamily runnerFamily,
                 String transport,
                 String runtimeBackend,
                 String workerProfile,
                 String faultShape,
                 List<TraceAnalyzerScenario> traceAnalyzers) {
            this.scenarioId = scenarioId;
            this.proofLineOwner = proofLineOwner;
            this.runnerFamily = runnerFamily;
            this.transport = transport;
            this.runtimeBackend = runtimeBackend;
            this.workerProfile = workerProfile;
            this.faultShape = faultShape;
            this.traceAnalyzers = List.copyOf(traceAnalyzers);
        }

        public String scenarioId() {
            return scenarioId;
        }

        public ProofLineOwner proofLineOwner() {
            return proofLineOwner;
        }

        public RunnerFamily runnerFamily() {
            return runnerFamily;
        }

        public String transport() {
            return transport;
        }

        public String runtimeBackend() {
            return runtimeBackend;
        }

        public String workerProfile() {
            return workerProfile;
        }

        public String faultShape() {
            return faultShape;
        }

        public List<TraceAnalyzerScenario> traceAnalyzers() {
            return traceAnalyzers;
        }
    }

    public static List<Scenario> scenarios() {
        return List.of(Scenario.values());
    }

    public static Optional<Scenario> scenarioForRunner(RunnerFamily runnerFamily) {
        if (runnerFamily == null) {
            return Optional.empty();
        }
        return Arrays.stream(Scenario.values())
                .filter(scenario -> scenario.runnerFamily() == runnerFamily)
                .findFirst();
    }

    public static Optional<TraceAnalyzerScenario> traceAnalyzerForChaosProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return Optional.empty();
        }
        return switch (profileName.trim().toUpperCase()) {
            case "ALL_FAILED_TERMINAL_CONVERGENCE" ->
                    Optional.of(TraceAnalyzerScenario.ALL_FAILED_TERMINAL_CONVERGENCE);
            case "MIXED_RESULT_TERMINAL_CONVERGENCE" ->
                    Optional.of(TraceAnalyzerScenario.MIXED_RESULT_TERMINAL_CONVERGENCE);
            case "LEASE_EXPIRY_REDISPATCH" ->
                    Optional.of(TraceAnalyzerScenario.LEASE_EXPIRY_REDISPATCH);
            case "LATE_STALE_RESULT_REPLAY" ->
                    Optional.of(TraceAnalyzerScenario.LATE_STALE_RESULT_REPLAY);
            default -> Optional.empty();
        };
    }

    public static Optional<TraceAnalyzerScenario> traceAnalyzerForSoakTerminalReason(String terminalReason) {
        if (terminalReason == null || terminalReason.isBlank()) {
            return Optional.empty();
        }
        return switch (terminalReason.trim().toUpperCase()) {
            case "ALL_MESSAGES_FAILED" -> Optional.of(TraceAnalyzerScenario.ALL_FAILED_TERMINAL_CONVERGENCE);
            case "MIXED_MESSAGE_RESULTS" -> Optional.of(TraceAnalyzerScenario.MIXED_RESULT_TERMINAL_CONVERGENCE);
            default -> Optional.empty();
        };
    }
}
