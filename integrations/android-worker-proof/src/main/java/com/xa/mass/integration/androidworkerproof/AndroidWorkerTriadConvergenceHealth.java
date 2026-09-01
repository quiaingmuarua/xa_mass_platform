package com.xa.mass.integration.androidworkerproof;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AndroidWorkerTriadConvergenceHealth {

    static final String SCENARIO = "triad-convergence-health";
    static final String BASELINE_PHASE = "baseline";
    static final String OUTAGE_PHASE = "outage";
    static final String RECOVERY_PHASE = "recovery";

    private final AndroidWorkerProofOptions options;
    private final AndroidWorkerTriad triad;
    private final TriadProofEvidence evidence;

    private AndroidWorkerTriadConvergenceHealth(
            AndroidWorkerProofOptions options
    ) {
        this.options = options;
        triad = new AndroidWorkerTriad(options);
        evidence = new TriadProofEvidence(options, SCENARIO, options.phase());
    }

    static void execute(AndroidWorkerProofOptions options) throws IOException {
        AndroidWorkerTriadConvergenceHealth proof =
                new AndroidWorkerTriadConvergenceHealth(options);
        Throwable failure = null;
        try {
            proof.run();
        } catch (ProofFailure error) {
            proof.evidence.failure(error);
            failure = error;
        } catch (RuntimeException | Error error) {
            proof.evidence.unexpectedFailure(error);
            failure = error;
        }
        try {
            proof.evidence.write();
        } catch (IOException writeFailure) {
            if (failure != null) {
                failure.addSuppressed(writeFailure);
            } else {
                throw writeFailure;
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void run() {
        switch (options.phase()) {
            case BASELINE_PHASE -> runBaseline();
            case OUTAGE_PHASE -> runOutage();
            case RECOVERY_PHASE -> runRecovery();
            default -> throw new IllegalArgumentException(
                    "Triad convergence phase must be baseline, outage, or recovery"
            );
        }
    }

    private void runBaseline() {
        options.baselineFile(false);
        Map<String, String> identities = triad.awaitAvailableWorld(Map.of());
        evidence.workers(identities);
        evidence.check("connectedWorkerCount", 3);
        evidence.check("schedulableWorkerCount", 3);
    }

    private void runOutage() {
        TriadProofEvidence.Baseline baseline = baseline();
        evidence.workers(baseline.workersByApplicationId());
        AndroidWorkerTriadTopology.WorkerAddress target =
                AndroidWorkerTriadTopology.OUTAGE_TARGET;
        String targetWorkerId = baseline.workerId(target);

        triad.awaitDeviceUnavailable(target);
        AndroidWorkerProofAssertions.awaitDisconnected(
                triad.runtime(),
                options.endpointManagerId(),
                targetWorkerId,
                options.maximumWait()
        );
        String unavailableState = AndroidWorkerProofAssertions.awaitUnavailable(
                triad.runtime(),
                targetWorkerId,
                options.maximumWait()
        );

        List<AndroidWorkerTriadTopology.WorkerAddress> survivors =
                AndroidWorkerTriadTopology.WORKERS.stream()
                        .filter(worker -> !worker.equals(target))
                        .toList();
        for (AndroidWorkerTriadTopology.WorkerAddress survivor : survivors) {
            triad.awaitAvailable(
                    survivor,
                    baseline.workerId(survivor)
            );
        }
        Map<String, AndroidRuntimeApiClient.TaskCall> calls =
                triad.callDelay(
                        survivors,
                        baseline.workersByApplicationId()
                );
        triad.requireSucceeded(
                calls,
                "triad.convergence.survivor-task-success"
        );
        if ("connected".equals(triad.runtime().networkState(
                options.endpointManagerId(),
                targetWorkerId
        ))) {
            throw new ProofFailure(
                    "triad.convergence.target-route",
                    "Stopped Android application became connected during outage"
            );
        }
        String finalTargetScheduling = triad.runtime().schedulingState(
                AndroidWorkerProofConstants.WORKER_GROUP_ID,
                targetWorkerId
        );
        if (!List.of("recovery", "cold").contains(finalTargetScheduling)) {
            throw new ProofFailure(
                    "triad.convergence.target-scheduling",
                    "Stopped Android application became schedulable during outage"
            );
        }
        Map<String, String> messageIds = new LinkedHashMap<>();
        calls.forEach((applicationId, call) -> messageIds.put(
                applicationId,
                call.messageId()
        ));
        evidence.check("outageApplicationId", target.applicationId());
        evidence.check("outageWorkerId", targetWorkerId);
        evidence.check("localMutationEstablished", true);
        evidence.check("outageSchedulingState", unavailableState);
        evidence.check("survivingWorkerCount", 2);
        evidence.check("survivorMessageIds", Map.copyOf(messageIds));
    }

    private void runRecovery() {
        TriadProofEvidence.Baseline baseline = baseline();
        Map<String, String> restored = triad.awaitAvailableWorld(
                baseline.workersByApplicationId()
        );
        evidence.workers(restored);
        AndroidWorkerTriadTopology.WorkerAddress target =
                AndroidWorkerTriadTopology.OUTAGE_TARGET;
        Map<String, AndroidRuntimeApiClient.TaskCall> calls = triad.callDelay(
                List.of(target),
                baseline.workersByApplicationId()
        );
        triad.requireSucceeded(
                calls,
                "triad.convergence.recovery-task-success"
        );
        evidence.check("recoveredApplicationId", target.applicationId());
        evidence.check("recoveredWorkerId", baseline.workerId(target));
        evidence.check("baselineIdentityMatched", true);
        evidence.check("connectedWorkerCount", 3);
        evidence.check("schedulableWorkerCount", 3);
        evidence.check(
                "recoveryMessageId",
                calls.get(target.applicationId()).messageId()
        );
    }

    private TriadProofEvidence.Baseline baseline() {
        return TriadProofEvidence.readBaseline(
                options.baselineFile(true),
                options,
                SCENARIO,
                BASELINE_PHASE
        );
    }
}
