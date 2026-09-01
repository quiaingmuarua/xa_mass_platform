package com.xa.mass.integration.androidworkerproof;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AndroidWorkerProofAssertions {

    private AndroidWorkerProofAssertions() {
    }

    static void awaitDeviceHealth(
            AndroidDeviceHostClient device,
            Duration maximumWait
    ) {
        ProofWait.until(
                maximumWait,
                () -> {
                    device.requireHealth();
                    return true;
                },
                Boolean.TRUE::equals,
                "device.health.ready",
                "Android device HTTP did not become ready",
                null
        );
    }

    static void requireDeviceEvents(AndroidDeviceHostClient device) {
        Set<String> actual = device.events();
        Set<String> expected = AndroidWorkerProofConstants.DEVICE_EVENTS;
        if (!actual.equals(expected)) {
            List<String> missing = new ArrayList<>(expected);
            missing.removeAll(actual);
            List<String> unexpected = new ArrayList<>(actual);
            unexpected.removeAll(expected);
            throw new ProofFailure(
                    "device.events.required",
                    "Android device events do not match the fixed Host assembly",
                    missing,
                    unexpected,
                    List.of()
            );
        }
    }

    static AndroidDeviceHostClient.Snapshot awaitRunning(
            AndroidDeviceHostClient device,
            Duration maximumWait,
            String expectedWorkerId
    ) {
        return ProofWait.until(
                maximumWait,
                device::snapshot,
                snapshot -> "RUNNING".equals(snapshot.state())
                        && snapshot.workerId() != null
                        && !snapshot.workerId().isBlank()
                        && (expectedWorkerId == null
                        || expectedWorkerId.equals(snapshot.workerId())),
                "device.lifecycle.running",
                "Android Worker did not reach RUNNING",
                expectedWorkerId
        );
    }

    static AndroidDeviceHostClient.Snapshot awaitStopped(
            AndroidDeviceHostClient device,
            Duration maximumWait,
            String expectedWorkerId
    ) {
        return ProofWait.until(
                maximumWait,
                device::snapshot,
                snapshot -> "STOPPED".equals(snapshot.state())
                        && (snapshot.workerId() == null
                        || expectedWorkerId.equals(snapshot.workerId())),
                "device.lifecycle.stopped",
                "Android Worker did not reach STOPPED",
                expectedWorkerId
        );
    }

    static void awaitConnected(
            AndroidRuntimeApiClient runtime,
            String endpointManagerId,
            String workerId,
            Duration maximumWait
    ) {
        ProofWait.until(
                maximumWait,
                () -> runtime.networkState(endpointManagerId, workerId),
                "connected"::equals,
                "network.connected",
                "Android Worker did not become connected",
                workerId
        );
    }

    static void awaitDisconnected(
            AndroidRuntimeApiClient runtime,
            String endpointManagerId,
            String workerId,
            Duration maximumWait
    ) {
        ProofWait.until(
                maximumWait,
                () -> runtime.networkState(endpointManagerId, workerId),
                state -> !"connected".equals(state),
                "network.disconnected",
                "Android Worker remained connected",
                workerId
        );
    }

    static String awaitHot(
            AndroidRuntimeApiClient runtime,
            String workerId,
            Duration maximumWait
    ) {
        return ProofWait.until(
                maximumWait,
                () -> runtime.schedulingState(
                        AndroidWorkerProofConstants.WORKER_GROUP_ID,
                        workerId
                ),
                "hot-score-overdue"::equals,
                "scheduling.hot",
                "Android Worker did not become schedulable",
                workerId
        );
    }

    static String awaitUnavailable(
            AndroidRuntimeApiClient runtime,
            String workerId,
            Duration maximumWait
    ) {
        return ProofWait.until(
                maximumWait,
                () -> runtime.schedulingState(
                        AndroidWorkerProofConstants.WORKER_GROUP_ID,
                        workerId
                ),
                state -> "recovery".equals(state) || "cold".equals(state),
                "scheduling.unavailable",
                "Android Worker remained schedulable",
                workerId
        );
    }

    static void requireProbe(
            AndroidRuntimeApiClient runtime,
            String endpointManagerId,
            String workerId
    ) {
        runtime.callWorker(
                endpointManagerId,
                workerId,
                AndroidWorkerProofConstants.WORKER_PROBE_EVENT,
                "null"
        ).requireSuccessful("Android Worker probe");
    }

    static void awaitResult(
            AndroidRuntimeApiClient runtime,
            String messageId,
            Duration maximumWait
    ) {
        ProofWait.until(
                maximumWait,
                () -> runtime.resultObserved(messageId),
                Boolean.TRUE::equals,
                "task-result.observed",
                "Android Worker Task result was not observed",
                messageId
        );
    }
}
