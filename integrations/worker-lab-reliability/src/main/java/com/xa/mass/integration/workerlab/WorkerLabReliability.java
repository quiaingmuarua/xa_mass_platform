package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.RuntimeApiClient.ExportResult;
import com.xa.mass.integration.workerlab.RuntimeApiClient.TaskItem;
import com.xa.mass.integration.workerlab.RuntimeApiClient.WorkerView;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
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

final class WorkerLabReliability {

    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";
    private static final String PHONE_EVENT =
            "extension.worker.phonenumber.e164";
    private static final String STRING_EVENT =
            "extension.worker.string.md5";
    private static final long MUTATED_LAB_SLOT = 901L;
    private static final long OBSERVATION_INTERVAL_MILLIS = 100L;
    private static final long CLEANUP_MAX_WAIT_MILLIS = 10_000L;

    private static final WorkerRef PHONE_ONE = new WorkerRef(
            PHONE_GROUP,
            "scenario-phone-number-worker-001"
    );
    private static final WorkerRef PHONE_TWO = new WorkerRef(
            PHONE_GROUP,
            "scenario-phone-number-worker-002"
    );
    private static final WorkerRef STRING_ONE = new WorkerRef(
            STRING_GROUP,
            "scenario-string-utils-worker-001"
    );
    private static final WorkerRef STRING_TWO = new WorkerRef(
            STRING_GROUP,
            "scenario-string-utils-worker-002"
    );
    private static final List<WorkerRef> CONTROLLED = List.of(
            PHONE_ONE,
            PHONE_TWO,
            STRING_ONE,
            STRING_TWO
    );

    private WorkerLabReliability() {
    }

    static void execute(WorkerLabReliabilityMain.Options options)
            throws Exception {
        ReliabilityEvidence evidence = new ReliabilityEvidence(
                options.proofId(),
                options.evidenceDirectory()
        );
        WorkerLabControlClient lab = new WorkerLabControlClient(
                new JsonHttpClient(
                        options.labControlBaseUrl(),
                        options.requestTimeout()
                )
        );
        RuntimeApiClient runtime = new RuntimeApiClient(
                new JsonHttpClient(
                        options.runtimeApiBaseUrl(),
                        options.requestTimeout()
                )
        );
        Map<WorkerRef, Map<String, Object>> originalProperties = null;
        Set<WorkerRef> propertiesPossiblyModified = new LinkedHashSet<>();
        RuntimeException failure = null;
        Map<String, Object> successFacts = Map.of();
        try {
            originalProperties = verifyInitialInventory(lab, evidence);
            successFacts = run(
                    options,
                    lab,
                    runtime,
                    evidence,
                    originalProperties,
                    propertiesPossiblyModified
            );
        } catch (RuntimeException error) {
            failure = error;
        } finally {
            RuntimeException cleanupFailure = originalProperties == null
                    ? null
                    : cleanup(
                            options,
                            lab,
                            evidence,
                            originalProperties,
                            propertiesPossiblyModified
                    );
            if (failure == null) {
                failure = cleanupFailure;
            } else if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        if (failure == null) {
            evidence.writeSummary("succeeded", successFacts);
            return;
        }
        try {
            evidence.writeSummary("failed", Map.of(
                    "failure", safeMessage(failure)
            ));
        } catch (IOException writeFailure) {
            failure.addSuppressed(writeFailure);
        }
        throw failure;
    }

    private static Map<String, Object> run(
            WorkerLabReliabilityMain.Options options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ReliabilityEvidence evidence,
            Map<WorkerRef, Map<String, Object>> originalProperties,
            Set<WorkerRef> propertiesPossiblyModified
    ) {
        CONTROLLED.forEach(worker -> {
            lab.start(worker.groupId(), worker.clientWorkerKey());
            evidence.record("startup", "worker-start-requested", Map.of(
                    "workerGroupId", worker.groupId(),
                    "clientWorkerKey", worker.clientWorkerKey()
            ));
        });
        Map<WorkerRef, String> workerIds = awaitControlledWorkers(
                "selected-workers-connected",
                options.maximumWait(),
                CONTROLLED,
                lab,
                runtime,
                options.endpointManagerId(),
                evidence
        );

        String stoppedWorkerId = workerIds.get(STRING_ONE);
        lab.scheduleStop(
                STRING_ONE.groupId(),
                STRING_ONE.clientWorkerKey(),
                options.scheduledStopDelayMillis()
        );
        evidence.record("fault", "worker-stop-scheduled", Map.of(
                "workerGroupId", STRING_ONE.groupId(),
                "clientWorkerKey", STRING_ONE.clientWorkerKey(),
                "delayMillis", options.scheduledStopDelayMillis()
        ));
        await(
                "scheduled-worker-stopped",
                options.maximumWait(),
                () -> lab.worker(
                        STRING_ONE.groupId(),
                        STRING_ONE.clientWorkerKey()
                ),
                WorkerLabReliability::isStopped
        );
        awaitNetworkState(
                "scheduled-worker-disconnected",
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                stoppedWorkerId,
                "disconnected"
        );
        String unavailableSchedulingState = await(
                "scheduled-worker-left-serviceable-hot",
                options.maximumWait(),
                () -> runtime.observeScheduling(
                        STRING_ONE.groupId(),
                        List.of(stoppedWorkerId)
                ).get(stoppedWorkerId),
                state -> "recovery".equals(state) || "cold".equals(state)
        );
        evidence.record("fault", "worker-converged-unavailable", Map.of(
                "workerId", stoppedWorkerId,
                "networkState", "disconnected",
                "schedulingState", unavailableSchedulingState
        ));

        lab.start(STRING_ONE.groupId(), STRING_ONE.clientWorkerKey());
        Map<WorkerRef, String> restarted = awaitControlledWorkers(
                "explicit-worker-restart",
                options.maximumWait(),
                List.of(STRING_ONE),
                lab,
                runtime,
                options.endpointManagerId(),
                evidence
        );
        require(
                stoppedWorkerId.equals(restarted.get(STRING_ONE)),
                "Explicit restart changed the Server-owned workerId"
        );
        evidence.record("recovery", "worker-explicitly-restarted", Map.of(
                "workerId", stoppedWorkerId
        ));

        stopAndAwait(
                STRING_TWO,
                workerIds.get(STRING_TWO),
                options,
                lab,
                runtime
        );
        Map<String, Object> changedProperties = new LinkedHashMap<>(
                originalProperties.get(STRING_TWO)
        );
        changedProperties.put("labSlot", MUTATED_LAB_SLOT);
        propertiesPossiblyModified.add(STRING_TWO);
        lab.replaceProperties(
                STRING_TWO.groupId(),
                STRING_TWO.clientWorkerKey(),
                changedProperties
        );
        evidence.record("properties", "worker-file-replaced", Map.of(
                "workerGroupId", STRING_TWO.groupId(),
                "clientWorkerKey", STRING_TWO.clientWorkerKey(),
                "labSlot", MUTATED_LAB_SLOT
        ));
        lab.start(STRING_TWO.groupId(), STRING_TWO.clientWorkerKey());
        Map<WorkerRef, String> propertyRestart = awaitControlledWorkers(
                "property-worker-restarted",
                options.maximumWait(),
                List.of(STRING_TWO),
                lab,
                runtime,
                options.endpointManagerId(),
                evidence
        );
        String propertyWorkerId = propertyRestart.get(STRING_TWO);
        require(
                workerIds.get(STRING_TWO).equals(propertyWorkerId),
                "Properties refresh changed the Server-owned workerId"
        );
        await(
                "runtime-properties-refreshed",
                options.maximumWait(),
                () -> runtime.previewWorkers(STRING_TWO.groupId())
                        .get(STRING_TWO.clientWorkerKey()),
                view -> view != null
                        && propertyWorkerId.equals(view.workerId())
                        && numberEquals(
                                view.workerProperties().get("labSlot"),
                                MUTATED_LAB_SLOT
                        )
        );
        evidence.record("properties", "runtime-preview-refreshed", Map.of(
                "workerId", propertyWorkerId,
                "labSlot", MUTATED_LAB_SLOT
        ));

        stopAndAwait(
                STRING_ONE,
                stoppedWorkerId,
                options,
                lab,
                runtime
        );
        stopAndAwait(
                STRING_TWO,
                propertyWorkerId,
                options,
                lab,
                runtime
        );
        List<String> stoppedStringWorkerIds = List.of(
                stoppedWorkerId,
                propertyWorkerId
        );
        Map<String, String> stoppedStringScheduling = await(
                "string-group-workers-converged-unavailable",
                options.maximumWait(),
                () -> runtime.observeScheduling(
                        STRING_GROUP,
                        stoppedStringWorkerIds
                ),
                states -> stoppedStringWorkerIds.stream().allMatch(
                        workerId -> isUnavailableSchedulingState(
                                states.get(workerId)
                        )
                )
        );
        evidence.record(
                "convergence",
                "string-group-workers-unavailable",
                Map.of(
                        "workerIds", stoppedStringWorkerIds,
                        "schedulingStates", stoppedStringScheduling
                )
        );

        TaskProof parkedStringTask = createTask(
                runtime,
                STRING_GROUP,
                Map.of(
                        "worker.labSlot",
                        Map.of("$eq", MUTATED_LAB_SLOT)
                ),
                STRING_EVENT,
                "value",
                List.of(
                        "worker-lab-string-1",
                        "worker-lab-string-2",
                        "worker-lab-string-3"
                )
        );
        ExportResult unavailableExport = runtime.exportResults(
                parkedStringTask.taskId(),
                Math.min(1_000, options.maximumWaitMillis())
        );
        require(
                !unavailableExport.ready(),
                "Unavailable Group Task completed before a matching Worker "
                        + "was restarted"
        );
        evidence.record("convergence", "task-remained-pending", Map.of(
                "workerGroupId", STRING_GROUP,
                "taskId", parkedStringTask.taskId(),
                "messageCount", parkedStringTask.messageIds().size()
        ));

        TaskProof phoneTask = createTask(
                runtime,
                PHONE_GROUP,
                Map.of(),
                PHONE_EVENT,
                "rawNumber",
                List.of(
                        "+14155552671",
                        "+442071838750",
                        "+8613800138000"
                )
        );
        awaitTaskResults(
                "independent-group-task-completed",
                options.maximumWait(),
                runtime,
                phoneTask
        );
        evidence.record("convergence", "independent-group-completed", Map.of(
                "workerGroupId", PHONE_GROUP,
                "taskId", phoneTask.taskId(),
                "messageCount", phoneTask.messageIds().size()
        ));

        lab.start(STRING_TWO.groupId(), STRING_TWO.clientWorkerKey());
        awaitControlledWorkers(
                "matching-worker-restarted",
                options.maximumWait(),
                List.of(STRING_TWO),
                lab,
                runtime,
                options.endpointManagerId(),
                evidence
        );
        awaitTaskResults(
                "parked-task-converged",
                options.maximumWait(),
                runtime,
                parkedStringTask
        );
        evidence.record("convergence", "parked-task-completed", Map.of(
                "workerGroupId", STRING_GROUP,
                "taskId", parkedStringTask.taskId(),
                "messageCount", parkedStringTask.messageIds().size()
        ));

        List<String> allMessages = new ArrayList<>();
        allMessages.addAll(phoneTask.messageIds());
        allMessages.addAll(parkedStringTask.messageIds());
        Collections.sort(allMessages);
        Map<String, String> identityEvidence = new LinkedHashMap<>();
        workerIds.forEach((worker, workerId) -> identityEvidence.put(
                worker.clientWorkerKey(),
                workerId
        ));
        return Map.of(
                "controlledWorkerIds", identityEvidence,
                "taskIds", List.of(
                        phoneTask.taskId(),
                        parkedStringTask.taskId()
                ),
                "messageIds", allMessages,
                "resultCount", allMessages.size(),
                "unavailableSchedulingState", unavailableSchedulingState,
                "propertiesRefreshObserved", true,
                "identityReuseObserved", true,
                "groupIsolationObserved", true
        );
    }

    private static Map<WorkerRef, Map<String, Object>> verifyInitialInventory(
            WorkerLabControlClient lab,
            ReliabilityEvidence evidence
    ) {
        List<WorkerSnapshot> workers = lab.workers();
        require(workers.size() == 20, "Worker Lab inventory is not 20");
        require(
                workers.stream().allMatch(WorkerLabReliability::isStopped),
                "Worker Lab initial-workers=none contract is not satisfied"
        );
        Set<WorkerRef> inventory = new LinkedHashSet<>();
        workers.forEach(worker -> inventory.add(new WorkerRef(
                worker.workerGroupId(),
                worker.clientWorkerKey()
        )));
        require(
                inventory.containsAll(CONTROLLED),
                "Worker Lab does not contain the controlled replicas"
        );
        Map<WorkerRef, Map<String, Object>> originalProperties =
                new LinkedHashMap<>();
        for (WorkerRef worker : CONTROLLED) {
            originalProperties.put(
                    worker,
                    lab.worker(
                            worker.groupId(),
                            worker.clientWorkerKey()
                    ).requireWorkerProperties()
            );
        }
        evidence.record("inventory", "initial-state-observed", Map.of(
                "workerCount", workers.size(),
                "controlledWorkerCount", CONTROLLED.size(),
                "initialState", "STOPPED"
        ));
        return Collections.unmodifiableMap(originalProperties);
    }

    private static Map<WorkerRef, String> awaitControlledWorkers(
            String phase,
            Duration maximumWait,
            List<WorkerRef> workers,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            String endpointManagerId,
            ReliabilityEvidence evidence
    ) {
        Map<WorkerRef, String> identities = await(
                phase,
                maximumWait,
                () -> currentConnectedIdentities(
                        workers,
                        lab,
                        runtime,
                        endpointManagerId
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

    private static Map<WorkerRef, String> currentConnectedIdentities(
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

    private static void stopAndAwait(
            WorkerRef worker,
            String workerId,
            WorkerLabReliabilityMain.Options options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime
    ) {
        lab.stop(worker.groupId(), worker.clientWorkerKey());
        await(
                "worker-stopped-" + worker.clientWorkerKey(),
                options.maximumWait(),
                () -> lab.worker(worker.groupId(), worker.clientWorkerKey()),
                WorkerLabReliability::isStopped
        );
        awaitNetworkState(
                "worker-disconnected-" + worker.clientWorkerKey(),
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                workerId,
                "disconnected"
        );
    }

    private static String awaitNetworkState(
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

    private static TaskProof createTask(
            RuntimeApiClient runtime,
            String workerGroupId,
            Map<String, Object> allocationRule,
            String eventCode,
            String inputName,
            List<String> inputValues
    ) {
        require(
                inputValues != null && !inputValues.isEmpty(),
                "Task proof inputs must be non-empty"
        );
        String taskId = runtime.createTask(workerGroupId, allocationRule);
        List<TaskItem> items = new ArrayList<>();
        for (String inputValue : inputValues) {
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
                )
        );
    }

    private static Set<String> awaitTaskResults(
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

    private static RuntimeException cleanup(
            WorkerLabReliabilityMain.Options options,
            WorkerLabControlClient lab,
            ReliabilityEvidence evidence,
            Map<WorkerRef, Map<String, Object>> originalProperties,
            Set<WorkerRef> propertiesPossiblyModified
    ) {
        RuntimeException failure = null;
        for (WorkerRef worker : CONTROLLED) {
            try {
                lab.cancelScheduledStop(
                        worker.groupId(),
                        worker.clientWorkerKey()
                );
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
            try {
                lab.stop(worker.groupId(), worker.clientWorkerKey());
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        try {
            Duration cleanupWait = Duration.ofMillis(Math.min(
                    options.maximumWaitMillis(),
                    CLEANUP_MAX_WAIT_MILLIS
            ));
            await(
                    "cleanup-workers-stopped",
                    cleanupWait,
                    () -> {
                        Map<WorkerRef, WorkerSnapshot> snapshots =
                                new LinkedHashMap<>();
                        for (WorkerRef worker : CONTROLLED) {
                            snapshots.put(
                                    worker,
                                    lab.worker(
                                            worker.groupId(),
                                            worker.clientWorkerKey()
                                    )
                            );
                        }
                        return snapshots;
                    },
                    snapshots -> CONTROLLED.stream().allMatch(
                            worker -> isStopped(snapshots.get(worker))
                    )
            );
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        for (WorkerRef worker : propertiesPossiblyModified) {
            try {
                lab.replaceProperties(
                        worker.groupId(),
                        worker.clientWorkerKey(),
                        originalProperties.get(worker)
                );
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        for (WorkerRef worker : propertiesPossiblyModified) {
            try {
                Map<String, Object> restored = lab.worker(
                        worker.groupId(),
                        worker.clientWorkerKey()
                ).requireWorkerProperties();
                require(
                        originalProperties.get(worker).equals(restored),
                        "Worker Lab Properties were not restored for "
                                + worker.clientWorkerKey()
                );
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        try {
            evidence.record(
                    "cleanup",
                    failure == null
                            ? "lab-state-restored"
                            : "lab-state-restore-incomplete",
                    Map.of(
                            "controlledWorkerCount", CONTROLLED.size(),
                            "restoredWorkerFileCount",
                            propertiesPossiblyModified.size()
                    )
            );
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        return failure;
    }

    private static boolean isUnavailableSchedulingState(String state) {
        return "recovery".equals(state) || "cold".equals(state);
    }

    private static <T> T await(
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
                "Worker Lab reliability phase timed out: " + phase
                        + " (latest=" + safeObservation(latest) + ")"
        );
        if (latestFailure != null) {
            timeout.addSuppressed(latestFailure);
        }
        throw timeout;
    }

    private static boolean isStopped(WorkerSnapshot snapshot) {
        return "STOPPED".equals(snapshot.desiredState())
                && "STOPPED".equals(snapshot.runtimeState());
    }

    private static boolean numberEquals(Object value, long expected) {
        return value instanceof Number number
                && number.longValue() == expected;
    }

    private static void sleep() {
        try {
            Thread.sleep(OBSERVATION_INTERVAL_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Worker Lab reliability proof was interrupted",
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private record WorkerRef(String groupId, String clientWorkerKey) {
    }

    private record TaskProof(String taskId, Set<String> messageIds) {
    }
}
