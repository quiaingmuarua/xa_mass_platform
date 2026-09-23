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
                () -> requireExpectedWorkerId(
                        device.snapshot(),
                        expectedWorkerId
                ),
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
                () -> requireExpectedWorkerId(
                        device.snapshot(),
                        expectedWorkerId
                ),
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

    static String awaitDisconnected(
            AndroidRuntimeApiClient runtime,
            String endpointManagerId,
            String workerId,
            Duration maximumWait
    ) {
        return ProofWait.until(
                maximumWait,
                () -> runtime.networkState(endpointManagerId, workerId),
                "disconnected"::equals,
                "network.disconnected",
                "Android Worker did not become disconnected",
                workerId
        );
    }

    static void awaitDeviceUnavailable(
            AndroidDeviceHostClient device,
            Duration maximumWait,
            String workerId
    ) {
        ProofWait.until(
                maximumWait,
                device::isUnavailable,
                Boolean.TRUE::equals,
                "device.host.unavailable",
                "Android device HTTP remained reachable",
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

    static void awaitSucceededResult(
            AndroidRuntimeApiClient runtime,
            String messageId,
            Duration maximumWait
    ) {
        ProofWait.until(
                maximumWait,
                () -> runtime.resultStatus(messageId),
                AndroidRuntimeApiClient.CallStatus.SUCCEEDED::equals,
                "task-result.observed",
                "Android Worker Task result was not observed",
                messageId
        );
    }

    static void awaitSucceededCall(
            AndroidRuntimeApiClient runtime,
            AndroidRuntimeApiClient.TaskCall call,
            Duration maximumWait
    ) {
        if (call.status() == AndroidRuntimeApiClient.CallStatus.SUCCEEDED) {
            return;
        }
        awaitSucceededResult(runtime, call.messageId(), maximumWait);
    }

    private static AndroidDeviceHostClient.Snapshot requireExpectedWorkerId(
            AndroidDeviceHostClient.Snapshot snapshot,
            String expectedWorkerId
    ) {
        if (expectedWorkerId != null
                && snapshot.workerId() != null
                && !expectedWorkerId.equals(snapshot.workerId())) {
            throw new ProofFailure(
                    "device.lifecycle.identity",
                    "Android Worker local identity changed",
                    List.of(),
                    List.of(),
                    List.of(expectedWorkerId, snapshot.workerId())
            );
        }
        return snapshot;
    }
}
