package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.RuntimeApiClient.WorkerView;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class WorkerLabConvergenceSupport {

    static final String PHONE_GROUP = "scenario-phone-number-workers";
    static final String STRING_GROUP = "scenario-string-utils-workers";
    static final String PHONE_EVENT =
            "extension.worker.phonenumber.e164";
    static final String STRING_EVENT = "extension.worker.string.md5";
    static final String CHECKPOINT_EVENT =
            "extension.worker.lab.checkpoint";
    static final String DELAY_EVENT = "extension.worker.lab.delay";
    static final String FAIL_EVENT = "extension.worker.lab.fail";

    static final List<WorkerRef> PHONE_WORKERS = workers(
            PHONE_GROUP,
            "convergence-phone-workers.jsonl"
    );
    static final List<WorkerRef> STRING_WORKERS = workers(
            STRING_GROUP,
            "convergence-string-workers.jsonl"
    );
    static final List<WorkerRef> CONVERGENCE_WORKERS = java.util.stream.Stream
            .concat(PHONE_WORKERS.stream(), STRING_WORKERS.stream())
            .toList();

    private static final long OBSERVATION_INTERVAL_MILLIS = 100L;

    private WorkerLabConvergenceSupport() {
    }

    static Map<WorkerRef, WorkerSnapshot> requireInventory(
            WorkerLabControlClient lab,
            List<WorkerRef> required
    ) {
        List<WorkerSnapshot> workers = lab.workers();
        Map<WorkerRef, WorkerSnapshot> byCoordinate = new LinkedHashMap<>();
        workers.forEach(snapshot -> {
            WorkerRef coordinate = new WorkerRef(
                    snapshot.workerGroupId(),
                    snapshot.labWorkerKey()
            );
            require(
                    byCoordinate.putIfAbsent(coordinate, snapshot) == null,
                    "Worker Lab contains a duplicate coordinate"
            );
        });
        require(
                byCoordinate.keySet().containsAll(required),
                "Worker Lab does not contain the required replicas"
        );
        return Collections.unmodifiableMap(byCoordinate);
    }

    static Map<WorkerRef, String> awaitConnected(
            String phase,
            WorkerLabHarnessOptions options,
            List<WorkerRef> workers,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence
    ) {
        Map<WorkerRef, String> identities = await(
                phase,
                options.maximumWait(),
                () -> currentConnectedIdentities(
                        workers,
                        lab,
                        runtime,
                        options.endpointManagerId()
                ),
                value -> value.size() == workers.size()
        );
        identities.forEach((worker, workerId) -> evidence.record(
                phase,
                "worker-connected",
                Map.of(
                        "workerGroupId", worker.groupId(),
                        "labWorkerKey", worker.labWorkerKey(),
                        "workerId", workerId
                )
        ));
        return identities;
    }

    static Map<WorkerRef, String> currentConnectedIdentities(
            List<WorkerRef> workers,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            String endpointManagerId
    ) {
        Map<WorkerRef, WorkerSnapshot> localWorkers = requireInventory(
                lab,
                workers
        );
        Map<String, Map<String, WorkerView>> viewsByGroup = workers.stream()
                .map(WorkerRef::groupId)
                .distinct()
                .collect(java.util.stream.Collectors.toMap(
                        groupId -> groupId,
                        runtime::previewWorkers,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<WorkerRef, String> candidates = new LinkedHashMap<>();
        for (WorkerRef worker : workers) {
            WorkerSnapshot local = localWorkers.get(worker);
            if (!"RUNNING".equals(local.desiredState())
                    || !"RUNNING".equals(local.runtimeState())
                    || local.workerId() == null
                    || local.workerId().isBlank()) {
                continue;
            }
            WorkerView view = viewsByGroup.get(worker.groupId())
                    .get(worker.labWorkerKey());
            if (view == null || !local.workerId().equals(view.workerId())) {
                continue;
            }
            candidates.put(worker, local.workerId());
        }
        if (candidates.size() != workers.size()) {
            return Map.of();
        }
        Map<String, String> network = runtime.observeNetwork(
                endpointManagerId,
                List.copyOf(candidates.values())
        );
        if (candidates.values().stream().anyMatch(
                workerId -> !"connected".equals(network.get(workerId))
        )) {
            return Map.of();
        }
        return Collections.unmodifiableMap(candidates);
    }

    static String awaitUnavailableScheduling(
            String phase,
            Duration maximumWait,
            RuntimeApiClient runtime,
            WorkerRef worker,
            String workerId
    ) {
        return await(
                phase,
                maximumWait,
                () -> runtime.observeScheduling(
                        worker.groupId(),
                        List.of(workerId)
                ).get(workerId),
                WorkerLabConvergenceSupport::isUnavailableSchedulingState
        );
    }

    static String awaitHotScheduling(
            String phase,
            Duration maximumWait,
            RuntimeApiClient runtime,
            WorkerRef worker,
            String workerId
    ) {
        return await(
                phase,
                maximumWait,
                () -> runtime.observeScheduling(
                        worker.groupId(),
                        List.of(workerId)
                ).get(workerId),
                WorkerLabConvergenceSupport::isHotSchedulingState
        );
    }

    static String awaitNetworkState(
            String phase,
            Duration maximumWait,
            RuntimeApiClient runtime,
            String endpointManagerId,
            String workerId,
            String expected
    ) {
        return await(
                phase,
                maximumWait,
                () -> runtime.observeNetwork(
                        endpointManagerId,
                        List.of(workerId)
                ).get(workerId),
                expected::equals
        );
    }

    static <T> T await(
            String phase,
            Duration maximumWait,
            Supplier<T> observation,
            Predicate<T> accepted
    ) {
        long deadline = System.nanoTime() + maximumWait.toNanos();
        RuntimeException latestFailure = null;
        T latest = null;
        do {
            try {
                latest = observation.get();
                if (accepted.test(latest)) {
                    return latest;
                }
            } catch (RuntimeException error) {
                latestFailure = error;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        IllegalStateException timeout = new IllegalStateException(
                "Worker Lab convergence phase timed out: " + phase
                        + " (latest=" + safeObservation(latest) + ")"
        );
        if (latestFailure != null) {
            timeout.addSuppressed(latestFailure);
        }
        throw timeout;
    }

    static boolean isStopped(WorkerSnapshot snapshot) {
        return "STOPPED".equals(snapshot.desiredState())
                && "STOPPED".equals(snapshot.runtimeState());
    }

    static boolean isRunning(WorkerSnapshot snapshot) {
        return "RUNNING".equals(snapshot.desiredState())
                && "RUNNING".equals(snapshot.runtimeState());
    }

    static boolean isUnavailableSchedulingState(String state) {
        return "recovery".equals(state) || "cold".equals(state);
    }

    static boolean isHotSchedulingState(String state) {
        return "held-hot".equals(state)
                || "hot-score-overdue".equals(state);
    }

    static boolean numberEquals(Object value, long expected) {
        return value instanceof Number number
                && number.longValue() == expected;
    }

    static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(OBSERVATION_INTERVAL_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Worker Lab convergence proof was interrupted",
                    error
            );
        }
    }

    private static String safeObservation(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return value.getClass().getSimpleName();
    }

    record WorkerRef(String groupId, String labWorkerKey) {
        String coordinate() {
            return groupId + "/" + labWorkerKey;
        }
    }

    private static List<WorkerRef> workers(String groupId, String filename) {
        List<WorkerRef> workers = new ArrayList<>();
        for (int line = 1; line <= 50; line++) {
            workers.add(new WorkerRef(groupId, filename + ":" + line));
        }
        return List.copyOf(workers);
    }
}
