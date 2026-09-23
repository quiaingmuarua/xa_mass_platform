package com.xa.mass.integration.androidworkerproof;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class AndroidWorkerTriadCorrectness {

    static final String SCENARIO = "triad-correctness";
    static final String INITIAL_PHASE = "initial";

    private final AndroidWorkerProofOptions options;
    private final AndroidWorkerTriad triad;
    private final TriadProofEvidence evidence;

    private AndroidWorkerTriadCorrectness(AndroidWorkerProofOptions options) {
        this.options = options;
        triad = new AndroidWorkerTriad(options);
        evidence = new TriadProofEvidence(options, SCENARIO, options.phase());
    }

    static void execute(AndroidWorkerProofOptions options) throws IOException {
        AndroidWorkerTriadCorrectness proof =
                new AndroidWorkerTriadCorrectness(options);
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
        if (!INITIAL_PHASE.equals(options.phase())) {
            throw new IllegalArgumentException(
                    "Triad correctness phase must be initial"
            );
        }
        options.baselineFile(false);
        Map<String, String> identities = triad.awaitAvailableWorld(Map.of());
        evidence.workers(identities);

        Map<String, AndroidRuntimeApiClient.TaskCall> calls = triad.callDelay(
                AndroidWorkerTriadTopology.WORKERS,
                identities
        );
        triad.requireSucceeded(
                calls,
                "triad.correctness.task-success"
        );
        Map<String, String> messageIds = new LinkedHashMap<>();
        calls.forEach((applicationId, call) -> messageIds.put(
                applicationId,
                call.messageId()
        ));
        evidence.check("workerIdsDistinct", true);
        evidence.check("connectedWorkerCount", 3);
        evidence.check("schedulableWorkerCount", 3);
        evidence.check("offeredItemCount", 3);
        evidence.check("succeededItemCount", 3);
        evidence.check("messageIdsByApplicationId", Map.copyOf(messageIds));
    }
}
