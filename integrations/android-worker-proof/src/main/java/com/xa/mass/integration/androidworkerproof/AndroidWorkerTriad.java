package com.xa.mass.integration.androidworkerproof;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AndroidWorkerTriad {

    private final AndroidWorkerProofOptions options;
    private final AndroidRuntimeApiClient runtime;
    private final Map<AndroidWorkerTriadTopology.WorkerAddress,
            AndroidDeviceHostClient> devices;

    AndroidWorkerTriad(AndroidWorkerProofOptions options) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        runtime = new AndroidRuntimeApiClient(new JsonHttpClient(
                options.serverBaseUrl(),
                options
        ));
        Map<AndroidWorkerTriadTopology.WorkerAddress,
                AndroidDeviceHostClient> assembled = new LinkedHashMap<>();
        for (AndroidWorkerTriadTopology.WorkerAddress worker
                : AndroidWorkerTriadTopology.WORKERS) {
            assembled.put(worker, new AndroidDeviceHostClient(
                    new JsonHttpClient(
                            worker.deviceBaseUrl(),
                            options
                    )
            ));
        }
        devices = Map.copyOf(assembled);
    }

    AndroidRuntimeApiClient runtime() {
        return runtime;
    }

    AndroidDeviceHostClient device(
            AndroidWorkerTriadTopology.WorkerAddress worker
    ) {
        AndroidDeviceHostClient device = devices.get(worker);
        if (device == null) {
            throw new IllegalArgumentException("Unknown Android Worker address");
        }
        return device;
    }

    Map<String, String> awaitAvailableWorld(
            Map<String, String> expectedWorkerIds
    ) {
        Map<String, String> identities = new LinkedHashMap<>();
        for (AndroidWorkerTriadTopology.WorkerAddress worker
                : AndroidWorkerTriadTopology.WORKERS) {
            String expectedWorkerId = expectedWorkerIds.get(
                    worker.applicationId()
            );
            identities.put(
                    worker.applicationId(),
                    awaitAvailable(worker, expectedWorkerId)
            );
        }
        requireIdentitySet(identities);
        return Map.copyOf(identities);
    }

    String awaitAvailable(
            AndroidWorkerTriadTopology.WorkerAddress worker,
            String expectedWorkerId
    ) {
        Duration maximumWait = options.maximumWait();
        AndroidDeviceHostClient device = device(worker);
        AndroidWorkerProofAssertions.awaitDeviceHealth(device, maximumWait);
        AndroidWorkerProofAssertions.requireDeviceEvents(device);
        AndroidDeviceHostClient.Snapshot snapshot =
                AndroidWorkerProofAssertions.awaitRunning(
                        device,
                        maximumWait,
                        expectedWorkerId
                );
        String workerId = snapshot.workerId();
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
        runtime.requirePropertiesRelation(
                options.endpointManagerId(),
                workerId,
                Map.of("packageName", worker.applicationId())
        );
        return workerId;
    }

    void awaitDeviceUnavailable(
            AndroidWorkerTriadTopology.WorkerAddress worker
    ) {
        ProofWait.until(
                options.maximumWait(),
                () -> device(worker).isUnavailable(),
                Boolean.TRUE::equals,
                "triad.device.unavailable",
                "Stopped Android application Host remained reachable",
                worker.applicationId()
        );
    }

    Map<String, AndroidRuntimeApiClient.TaskCall> callDelay(
            List<AndroidWorkerTriadTopology.WorkerAddress> workers,
            Map<String, String> workerIdsByApplicationId
    ) {
        List<AndroidRuntimeApiClient.TaskItemCall> requests = workers.stream()
                .map(worker -> new AndroidRuntimeApiClient.TaskItemCall(
                        AndroidWorkerProofConstants.DELAY_EVENT,
                        Map.of("delayMillis", 100L),
                        AndroidWorkerTriadTopology.allocationRule(
                                worker,
                                workerIdsByApplicationId.get(
                                        worker.applicationId()
                                )
                        )
                ))
                .toList();
        List<AndroidRuntimeApiClient.TaskCall> calls = runtime.callItems(
                requests,
                AndroidWorkerProofConstants.TASK_CALL_OBSERVATION_WAIT_MILLIS
        );
        Map<String, AndroidRuntimeApiClient.TaskCall> byApplicationId =
                new LinkedHashMap<>();
        for (int index = 0; index < workers.size(); index++) {
            byApplicationId.put(
                    workers.get(index).applicationId(),
                    calls.get(index)
            );
        }
        return Map.copyOf(byApplicationId);
    }

    void requireSucceeded(
            Map<String, AndroidRuntimeApiClient.TaskCall> calls,
            String invariant
    ) {
        Set<String> messageIds = new LinkedHashSet<>();
        for (Map.Entry<String, AndroidRuntimeApiClient.TaskCall> call
                : calls.entrySet()) {
            if (!messageIds.add(call.getValue().messageId())) {
                throw new ProofFailure(
                        invariant,
                        "Android Worker triad Task witness IDs are duplicated",
                        List.of(),
                        List.of(),
                        List.of(call.getKey())
                );
            }
            AndroidWorkerProofAssertions.awaitSucceededCall(
                    runtime,
                    call.getValue(),
                    options.maximumWait()
            );
        }
    }

    private static void requireIdentitySet(Map<String, String> identities) {
        Set<String> expectedApplications =
                AndroidWorkerTriadTopology.applicationIds();
        if (!identities.keySet().equals(expectedApplications)) {
            throw new ProofFailure(
                    "triad.identity.applications",
                    "Android Worker triad application identities differ"
            );
        }
        Set<String> workerIds = new LinkedHashSet<>();
        for (String workerId : identities.values()) {
            if (workerId == null || workerId.isBlank()
                    || !workerIds.add(workerId)) {
                throw new ProofFailure(
                        "triad.identity.workers",
                        "Android Worker triad worker IDs are invalid or duplicated"
                );
            }
        }
    }
}
