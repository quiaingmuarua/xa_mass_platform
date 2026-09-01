package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

final class AndroidWorkerConvergenceHealth {

    static final String SCENARIO = "convergence-health";
    static final String ACTIVE_PHASE = "active";
    static final String TERMINAL_PHASE = "terminal";
    static final String SERVER_RESTART_PHASE = "server-restart";

    private final AndroidWorkerProofOptions options;
    private final AndroidDeviceHostClient device;
    private final AndroidRuntimeApiClient runtime;
    private final ProofEvidence evidence;

    private AndroidWorkerConvergenceHealth(AndroidWorkerProofOptions options) {
        this.options = options;
        device = new AndroidDeviceHostClient(new JsonHttpClient(
                options.deviceBaseUrl(),
                options.requestTimeout()
        ));
        runtime = new AndroidRuntimeApiClient(new JsonHttpClient(
                options.serverBaseUrl(),
                options.requestTimeout()
        ));
        evidence = new ProofEvidence(options, SCENARIO, options.phase());
    }

    static void execute(AndroidWorkerProofOptions options) throws IOException {
        AndroidWorkerConvergenceHealth proof =
                new AndroidWorkerConvergenceHealth(options);
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
            case ACTIVE_PHASE -> runActive();
            case TERMINAL_PHASE -> runTerminal();
            case SERVER_RESTART_PHASE -> runServerRestart();
            default -> throw new IllegalArgumentException(
                    "Convergence phase must be active, terminal, or server-restart"
            );
        }
    }

    private void runActive() {
        options.baselineFile(false);
        Duration maximumWait = options.maximumWait();
        AndroidWorkerProofAssertions.awaitDeviceHealth(device, maximumWait);
        AndroidWorkerProofAssertions.requireDeviceEvents(device);
        AndroidDeviceHostClient.Snapshot snapshot =
                AndroidWorkerProofAssertions.awaitRunning(
                        device,
                        maximumWait,
                        null
                );
        String workerId = snapshot.workerId();
        evidence.workerId(workerId);
        AndroidWorkerProofAssertions.awaitConnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                maximumWait
        );
        AndroidWorkerProofAssertions.awaitHot(runtime, workerId, maximumWait);

        AndroidRuntimeApiClient.DirectTarget failed = runtime.callWorker(
                options.endpointManagerId(),
                workerId,
                AndroidWorkerProofConstants.FAIL_EVENT,
                Jsons.toJson(Map.of())
        );
        if (!"observed".equals(failed.status())
                || failed.outcomeCode() == null
                || !failed.outcomeCode().matches("3\\d{3}")) {
            throw new ProofFailure(
                    "convergence.fail-isolation",
                    "Android Worker FAIL was not observed as a Worker failure"
            );
        }
        AndroidWorkerProofAssertions.requireProbe(
                runtime,
                options.endpointManagerId(),
                workerId
        );
        AndroidWorkerProofAssertions.awaitConnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                maximumWait
        );
        evidence.check("failOutcomeCode", failed.outcomeCode());
        evidence.check("postFailProbeObserved", true);

        AndroidRuntimeApiClient.TaskCall delayed = runtime.callItem(
                AndroidWorkerProofConstants.DELAY_EVENT,
                Map.of("delayMillis", 10_000L),
                250L
        );
        if (delayed.status()
                != AndroidRuntimeApiClient.CallStatus.NOT_OBSERVED) {
            throw new ProofFailure(
                    "convergence.delay.in-flight",
                    "Long DELAY completed before the in-flight checkpoint"
            );
        }
        ProofWait.until(
                maximumWait,
                device::snapshot,
                local -> local.activeDelayCount() == 1L,
                "convergence.delay.active",
                "Android DELAY did not enter the Handler",
                workerId
        );
        String closeOutcome = runtime.closeCurrentConnection(
                options.endpointManagerId(),
                workerId
        );
        if (!"close-started".equals(closeOutcome)) {
            throw new ProofFailure(
                    "convergence.connection-mutation",
                    "Adapter did not establish the physical close mutation"
            );
        }
        AndroidWorkerProofAssertions.awaitResult(
                runtime,
                delayed.messageId(),
                maximumWait
        );
        AndroidWorkerProofAssertions.awaitRunning(
                device,
                maximumWait,
                workerId
        );
        AndroidWorkerProofAssertions.awaitConnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                maximumWait
        );
        AndroidWorkerProofAssertions.awaitHot(runtime, workerId, maximumWait);
        evidence.check("longDelayMessageId", delayed.messageId());
        evidence.check("activeDelayObserved", true);
        evidence.check("closeCurrentOutcome", closeOutcome);
        evidence.check("longDelayResultObserved", true);
        evidence.check("postMutationConnected", true);
    }

    private void runTerminal() {
        ProofEvidence.Baseline baseline = activeBaseline();
        String workerId = baseline.workerId();
        evidence.workerId(workerId);
        evidence.baselineIdentityMatched(true);
        AndroidWorkerProofAssertions.awaitDeviceHealth(
                device,
                options.maximumWait()
        );
        AndroidDeviceHostClient.Snapshot stopped =
                AndroidWorkerProofAssertions.awaitStopped(
                        device,
                        options.maximumWait(),
                        workerId
                );
        evidence.check("endpointExhaustedState", stopped.state());
        evidence.check("endpointExhaustedDiagnosticPresent",
                stopped.diagnosticMessage() != null);
    }

    private void runServerRestart() {
        ProofEvidence.Baseline baseline = activeBaseline();
        String workerId = baseline.workerId();
        evidence.workerId(workerId);
        evidence.baselineIdentityMatched(true);
        AndroidWorkerProofAssertions.awaitDeviceHealth(
                device,
                options.maximumWait()
        );
        ProofWait.observeFor(
                Duration.ofSeconds(3L),
                () -> {
                    AndroidDeviceHostClient.Snapshot snapshot = device.snapshot();
                    if (!"STOPPED".equals(snapshot.state())
                            || (snapshot.workerId() != null
                            && !workerId.equals(snapshot.workerId()))) {
                        throw new ProofFailure(
                                "server-restart.no-automatic-start",
                                "Android Worker started automatically"
                        );
                    }
                    if ("connected".equals(runtime.networkState(
                            options.endpointManagerId(),
                            workerId
                    ))) {
                        throw new ProofFailure(
                                "server-restart.no-automatic-route",
                                "Android Worker restored its route automatically"
                        );
                    }
                },
                "server-restart.no-automatic-start",
                "Android Worker did not remain stopped after Server restart"
        );
        evidence.check("noAutomaticStartObservationMillis", 3_000);

        device.start();
        AndroidWorkerProofAssertions.awaitRunning(
                device,
                options.maximumWait(),
                workerId
        );
        AndroidWorkerProofAssertions.awaitConnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                options.maximumWait()
        );
        String scheduling = AndroidWorkerProofAssertions.awaitHot(
                runtime,
                workerId,
                options.maximumWait()
        );
        AndroidRuntimeApiClient.TaskCall witness = runtime.callItem(
                AndroidWorkerProofConstants.DELAY_EVENT,
                Map.of("delayMillis", 100L),
                Math.min(options.maximumWait().toMillis(), 30_000L)
        );
        if (witness.status() != AndroidRuntimeApiClient.CallStatus.SUCCEEDED) {
            throw new ProofFailure(
                    "server-restart.witness",
                    "Android Worker recovery witness did not succeed"
            );
        }
        evidence.check("explicitStartConnected", true);
        evidence.check("explicitStartSchedulingState", scheduling);
        evidence.check("recoveryWitnessMessageId", witness.messageId());
    }

    private ProofEvidence.Baseline activeBaseline() {
        return ProofEvidence.readBaseline(
                options.baselineFile(true),
                options,
                SCENARIO,
                ACTIVE_PHASE
        );
    }
}
