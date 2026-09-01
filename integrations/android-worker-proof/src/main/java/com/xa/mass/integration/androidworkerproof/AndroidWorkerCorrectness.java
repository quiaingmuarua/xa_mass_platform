package com.xa.mass.integration.androidworkerproof;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class AndroidWorkerCorrectness {

    static final String SCENARIO = "correctness";
    static final String INITIAL_PHASE = "initial";
    static final String PROCESS_RESTART_PHASE = "process-restart";

    private final AndroidWorkerProofOptions options;
    private final AndroidDeviceHostClient device;
    private final AndroidRuntimeApiClient runtime;
    private final ProofEvidence evidence;

    private AndroidWorkerCorrectness(AndroidWorkerProofOptions options) {
        this.options = options;
        device = new AndroidDeviceHostClient(new JsonHttpClient(
                options.deviceBaseUrl(),
                options
        ));
        runtime = new AndroidRuntimeApiClient(new JsonHttpClient(
                options.serverBaseUrl(),
                options
        ));
        evidence = new ProofEvidence(options, SCENARIO, options.phase());
    }

    static void execute(AndroidWorkerProofOptions options) throws IOException {
        AndroidWorkerCorrectness proof = new AndroidWorkerCorrectness(options);
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
            case INITIAL_PHASE -> runInitial();
            case PROCESS_RESTART_PHASE -> runProcessRestart();
            default -> throw new IllegalArgumentException(
                    "Correctness phase must be initial or process-restart"
            );
        }
    }

    private void runInitial() {
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
        String scheduling = AndroidWorkerProofAssertions.awaitHot(
                runtime,
                workerId,
                maximumWait
        );
        AndroidWorkerProofAssertions.requireProbe(
                runtime,
                options.endpointManagerId(),
                workerId
        );
        runtime.requirePropertiesRelation(options.endpointManagerId(), workerId);

        Set<String> messageIds = new LinkedHashSet<>();
        int succeeded = 0;
        for (int index = 0; index < 10; index++) {
            AndroidRuntimeApiClient.TaskCall call = runtime.callItem(
                    AndroidWorkerProofConstants.DELAY_EVENT,
                    Map.of("delayMillis", 100L),
                    Math.min(maximumWait.toMillis(), 30_000L)
            );
            if (!messageIds.add(call.messageId())) {
                throw new ProofFailure(
                        "correctness.message-identities",
                        "Android Worker correctness message IDs are duplicated"
                );
            }
            if (call.status() != AndroidRuntimeApiClient.CallStatus.SUCCEEDED) {
                throw new ProofFailure(
                        "correctness.task-success",
                        "Android Worker correctness Task did not succeed",
                        java.util.List.of(call.messageId()),
                        java.util.List.of(),
                        java.util.List.of()
                );
            }
            succeeded++;
        }
        evidence.check("deviceEventCodes", Set.copyOf(
                AndroidWorkerProofConstants.DEVICE_EVENTS
        ));
        evidence.check("initialNetworkState", "connected");
        evidence.check("initialSchedulingState", scheduling);
        evidence.check("propertiesMatched", true);
        evidence.check("offeredItemCount", 10);
        evidence.check("succeededItemCount", succeeded);
        evidence.check("messageIds", Set.copyOf(messageIds));

        device.stop();
        AndroidWorkerProofAssertions.awaitStopped(
                device,
                maximumWait,
                workerId
        );
        AndroidWorkerProofAssertions.awaitDisconnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                maximumWait
        );
        device.start();
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
        AndroidWorkerProofAssertions.requireProbe(
                runtime,
                options.endpointManagerId(),
                workerId
        );
        evidence.check("explicitRestartIdentityMatched", true);
        evidence.check("explicitRestartProbeObserved", true);
    }

    private void runProcessRestart() {
        ProofEvidence.Baseline baseline = ProofEvidence.readBaseline(
                options.baselineFile(true),
                options,
                SCENARIO,
                INITIAL_PHASE
        );
        String workerId = baseline.workerId();
        Duration maximumWait = options.maximumWait();
        evidence.workerId(workerId);
        evidence.baselineIdentityMatched(true);
        AndroidWorkerProofAssertions.awaitDisconnected(
                runtime,
                options.endpointManagerId(),
                workerId,
                maximumWait
        );
        String unavailable = AndroidWorkerProofAssertions.awaitUnavailable(
                runtime,
                workerId,
                maximumWait
        );
        evidence.check("processStopSchedulingState", unavailable);
        System.out.println(
                AndroidWorkerProofConstants.PROCESS_STOP_OBSERVED_MARKER
        );
        System.out.flush();

        AndroidWorkerProofAssertions.awaitDeviceHealth(device, maximumWait);
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
        AndroidWorkerProofAssertions.requireProbe(
                runtime,
                options.endpointManagerId(),
                workerId
        );
        AndroidRuntimeApiClient.TaskCall witness = runtime.callItem(
                AndroidWorkerProofConstants.DELAY_EVENT,
                Map.of("delayMillis", 100L),
                Math.min(maximumWait.toMillis(), 30_000L)
        );
        if (witness.status() != AndroidRuntimeApiClient.CallStatus.SUCCEEDED) {
            throw new ProofFailure(
                    "process-restart.task-success",
                    "Android Worker process-restart witness did not succeed"
            );
        }
        evidence.check("processRestartConnected", true);
        evidence.check("processRestartSchedulingState", "hot-score-overdue");
        evidence.check("processRestartMessageId", witness.messageId());
    }
}
