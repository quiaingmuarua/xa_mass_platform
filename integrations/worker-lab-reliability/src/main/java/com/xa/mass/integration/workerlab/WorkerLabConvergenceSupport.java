package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.RuntimeApiClient.ExportResult;
import com.xa.mass.integration.workerlab.RuntimeApiClient.TaskItem;
import com.xa.mass.integration.workerlab.RuntimeApiClient.WorkerView;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    static final WorkerRef PHONE_ONE = worker(PHONE_GROUP, "phone-number", 1);
    static final WorkerRef PHONE_TWO = worker(PHONE_GROUP, "phone-number", 2);
    static final WorkerRef STRING_ONE = worker(STRING_GROUP, "string-utils", 1);
    static final WorkerRef STRING_TWO = worker(STRING_GROUP, "string-utils", 2);
    static final List<WorkerRef> CONTROLLED = List.of(
            PHONE_ONE,
            PHONE_TWO,
            STRING_ONE,
            STRING_TWO
    );

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
                    snapshot.clientWorkerKey()
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
                        "clientWorkerKey", worker.clientWorkerKey(),
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
        Map<WorkerRef, String> candidates = new LinkedHashMap<>();
        for (WorkerRef worker : workers) {
            WorkerSnapshot local = lab.worker(
                    worker.groupId(),
                    worker.clientWorkerKey()
            );
            if (!"RUNNING".equals(local.desiredState())
                    || !"RUNNING".equals(local.runtimeState())
                    || local.workerId() == null
                    || local.workerId().isBlank()) {
                continue;
            }
            WorkerView view = runtime.previewWorkers(worker.groupId())
                    .get(worker.clientWorkerKey());
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

    static TaskProof createTask(
            RuntimeApiClient runtime,
            String workerGroupId,
            Map<String, Object> allocationRule,
            String eventCode,
            String inputName,
            List<?> inputValues,
            int maxRetryTimes
    ) {
        require(
                inputValues != null && !inputValues.isEmpty(),
                "Task proof inputs must be non-empty"
        );
        String taskId = runtime.createTask(
                workerGroupId,
                allocationRule,
                maxRetryTimes
        );
        List<TaskItem> items = new ArrayList<>();
        for (Object inputValue : inputValues) {
            items.add(new TaskItem(
                    "message-" + UUID.randomUUID(),
                    eventCode,
                    Map.of(inputName, inputValue)
            ));
        }
        runtime.appendItems(taskId, items);
        runtime.approveTask(taskId);
        return new TaskProof(
                taskId,
                items.stream().map(TaskItem::messageId).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()
                ),
                workerGroupId
        );
    }

    static Set<String> awaitTaskFinalResults(
            String phase,
            Duration maximumWait,
            RuntimeApiClient runtime,
            TaskProof task
    ) {
        ExportResult result = await(
                phase,
                maximumWait,
                () -> runtime.exportResults(
                        task.taskId(),
                        Math.min(2_000, maximumWait.toMillis())
                ),
                ExportResult::ready
        );
        require(
                task.messageIds().equals(result.messageIds()),
                "Task Result messageIds do not match submitted Items"
        );
        return result.messageIds();
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

    private static WorkerRef worker(
            String group,
            String capability,
            int replica
    ) {
        return new WorkerRef(
                group,
                "scenario-" + capability + "-worker-%03d".formatted(replica)
        );
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

    record WorkerRef(String groupId, String clientWorkerKey) {
    }

    record TaskProof(
            String taskId,
            Set<String> messageIds,
            String workerGroupId
    ) {
        TaskProof {
            messageIds = Set.copyOf(new LinkedHashSet<>(messageIds));
        }
    }
}
