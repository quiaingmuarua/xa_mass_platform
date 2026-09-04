package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CONVERGENCE_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.await;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitConnected;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.isStopped;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.require;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.safeMessage;

import com.xa.mass.integration.workerlab.ConvergenceWorkload.Batch;
import com.xa.mass.integration.workerlab.RuntimeApiClient.CallStatus;
import com.xa.mass.integration.workerlab.RuntimeApiClient.PrecomputedWitness;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class WorkerStateConvergence {

    static final String LANE = "state-server-convergence";
    private static final String SLOT_PROPERTY = "convergenceSlot";
    private static final String MATCH_PROPERTY = "worker.convergenceSlot";
    private static final String MUTATED_SLOT = "B";
    private static final String UNMATCHED_SLOT = "C";

    private static final List<WorkerRef> STOPPED_SAMPLE = joined(
            PHONE_WORKERS.subList(0, 5),
            STRING_WORKERS.subList(0, 5)
    );
    private static final List<WorkerRef> MUTATED_SAMPLE = joined(
            PHONE_WORKERS.subList(5, 7),
            STRING_WORKERS.subList(5, 7)
    );
    private static final WorkerRef SLOT_C_WORKER = STRING_WORKERS.get(7);
    private static final List<WorkerRef> SERVER_RESTART_RUNNING_WORKERS =
            CONVERGENCE_WORKERS.stream()
                    .filter(worker -> !SLOT_C_WORKER.equals(worker))
                    .toList();
    private static final List<WorkerRef> STRING_WORKERS_EXCEPT_SLOT_C =
            STRING_WORKERS.stream()
                    .filter(worker -> !SLOT_C_WORKER.equals(worker))
                    .toList();

    private WorkerStateConvergence() {
    }

    static void beforeServerRestart(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        ConvergenceEvidence evidence = ConvergenceEvidence.begin(
                options.proofId(),
                LANE,
                options.evidenceDirectory()
        );
        WorkerLabControlClient lab = options.labClient();
        RuntimeApiClient runtime = options.runtimeClient();
        try {
            requireExactWorld(lab);
            Map<WorkerRef, String> identities = awaitConnected(
                    "baseline-connected",
                    options,
                    CONVERGENCE_WORKERS,
                    lab,
                    runtime,
                    evidence
            );
            awaitHot("baseline-hot", options, runtime, identities);
            stopOnceAndAwait(
                    "slot-c-pre-work-stop",
                    options,
                    lab,
                    runtime,
                    identities,
                    List.of(SLOT_C_WORKER),
                    evidence
            );

            stopOnceAndAwait(
                    "sample-outage",
                    options,
                    lab,
                    runtime,
                    identities,
                    STOPPED_SAMPLE,
                    evidence
            );
            startOnceAndAwait(
                    "sample-recovery",
                    options,
                    lab,
                    runtime,
                    STOPPED_SAMPLE,
                    evidence
            );

            stopLocally("property-stop", options, lab, MUTATED_SAMPLE, evidence);
            mutatePropertiesOnce(
                    "property-change",
                    lab,
                    MUTATED_SAMPLE,
                    MUTATED_SLOT,
                    evidence
            );
            Map<WorkerRef, String> mutatedIds = startOnceAndAwait(
                    "property-restart",
                    options,
                    lab,
                    runtime,
                    MUTATED_SAMPLE,
                    evidence
            );
            awaitProjectedSlot(
                    "property-projected",
                    options,
                    runtime,
                    mutatedIds,
                    MUTATED_SLOT
            );
            stopLocally(
                    "string-group-outage",
                    options,
                    lab,
                    STRING_WORKERS_EXCEPT_SLOT_C,
                    evidence
            );
            awaitUnavailable(
                    "string-group-outage",
                    options,
                    runtime,
                    identities,
                    STRING_WORKERS,
                    evidence,
                    "disconnected"::equals
            );
            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    options.proofId()
            );
            List<Batch> outageWave = workload.submitWave(
                    "wave-1",
                    Map.of(),
                    null
            );
            Batch phoneOutage = workload.requireBatch(outageWave, PHONE_GROUP);
            Batch stringOutage = workload.requireBatch(outageWave, STRING_GROUP);
            workload.awaitWitness(phoneOutage, options.maximumWait());
            require(
                    workload.immediateWitnessStatus(stringOutage)
                            == CallStatus.NOT_OBSERVED,
                    "String witness items:call status was not NOT_OBSERVED while "
                            + "its entire Group was unavailable"
            );
            require(
                    !workload.witnessObserved(stringOutage),
                    "String witness already had a successful Result while its entire "
                            + "Group was unavailable"
            );
            evidence.record("string-group-outage", "work-remained-due", Map.of(
                    "workerGroupId", STRING_GROUP,
                    "witnessMessageId", stringOutage.witnessMessageId()
            ));
            startOnceAndAwaitConnected(
                    "string-group-recovery",
                    options,
                    lab,
                    runtime,
                    STRING_WORKERS_EXCEPT_SLOT_C,
                    evidence
            );
            workload.awaitWitness(stringOutage, options.maximumWait());

            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-2", Map.of(), null),
                    options
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-3", Map.of(), null),
                    options
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-4", Map.of(), null),
                    options
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-5", Map.of(), null),
                    options
            );

            require(
                    isStopped(lab.worker(
                            SLOT_C_WORKER.groupId(),
                            SLOT_C_WORKER.labWorkerKey()
                    )),
                    "Slot C Worker must remain stopped before wave-6"
            );
            awaitUnavailable(
                    "slot-c-pre-restart-unavailable",
                    options,
                    runtime,
                    Map.of(
                            SLOT_C_WORKER,
                            workerId(identities, SLOT_C_WORKER)
                    ),
                    List.of(SLOT_C_WORKER),
                    evidence,
                    "disconnected"::equals
            );
            List<Batch> restartWave = workload.submitWave(
                    "wave-6",
                    Map.of(),
                    null
            );
            awaitWaveWitnesses(workload, restartWave, options);
            String propertyMessageId = options.proofId()
                    + "-precomputed-slot-c";
            PrecomputedWitness propertyWitness =
                    runtime.submitPrecomputedWitness(
                            STRING_GROUP,
                            Map.of(MATCH_PROPERTY, Map.of(
                                    "$eq",
                                    UNMATCHED_SLOT
                            )),
                            propertyMessageId,
                            WorkerLabConvergenceSupport.STRING_EVENT,
                            Map.of("value", "property-slot-c")
                    );
            require(
                    runtime.loadResultStatuses(
                            propertyWitness.taskId(),
                            List.of(propertyWitness.messageId())
                    ).get(propertyWitness.messageId())
                            == CallStatus.NOT_OBSERVED,
                    "PRECOMPUTED property witness was observed before slot C existed"
            );
            new StateConvergencePhaseState(
                    options.proofId(),
                    Instant.now(),
                    coordinates(identities),
                    workload.batches(),
                    propertyWitness.taskId(),
                    propertyWitness.messageId()
            ).save(phaseStatePath);
            evidence.record("server-restart", "work-submitted-before-restart", Map.of(
                    "offeredItemCount", workload.offeredItemCount(),
                    "propertyWitnessTaskId", propertyWitness.taskId(),
                    "propertyWitnessMessageId", propertyWitness.messageId(),
                    "workerCount", identities.size()
            ));
        } catch (RuntimeException error) {
            writeFailure(evidence, "before-server-restart", error);
            throw error;
        }
    }

    static void afterServerRestart(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        StateConvergencePhaseState state = StateConvergencePhaseState.load(
                phaseStatePath
        );
        require(
                options.proofId().equals(state.proofId()),
                "State convergence proofId changed between phases"
        );
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        WorkerLabControlClient lab = options.labClient();
        RuntimeApiClient runtime = options.runtimeClient();
        try {
            requireServerRestartWorld(lab);
            Map<WorkerRef, String> reconnected = awaitConnected(
                    "server-restarted-connected",
                    options,
                    SERVER_RESTART_RUNNING_WORKERS,
                    lab,
                    runtime,
                    evidence
            );
            requireStableIdentities(state, reconnected);
            awaitHot("server-restarted-hot", options, runtime, reconnected);

            String expectedSlotCWorkerId = state.workerIdsByCoordinate().get(
                    SLOT_C_WORKER.coordinate()
            );
            require(
                    expectedSlotCWorkerId != null,
                    "Phase state is missing the slot C Worker identity"
            );
            awaitUnavailable(
                    "server-restarted-slot-c-unavailable",
                    options,
                    runtime,
                    Map.of(SLOT_C_WORKER, expectedSlotCWorkerId),
                    List.of(SLOT_C_WORKER),
                    evidence,
                    networkState -> "disconnected".equals(networkState)
                            || "unknown".equals(networkState)
            );

            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    options.proofId(),
                    state.batches()
            );
            mutatePropertiesOnce(
                    "slot-c-change",
                    lab,
                    List.of(SLOT_C_WORKER),
                    UNMATCHED_SLOT,
                    evidence
            );
            Map<WorkerRef, String> slotCWorker = startOnceAndAwait(
                    "slot-c-start",
                    options,
                    lab,
                    runtime,
                    List.of(SLOT_C_WORKER),
                    evidence
            );
            require(
                    expectedSlotCWorkerId.equals(
                            workerId(slotCWorker, SLOT_C_WORKER)
                    ),
                    "Slot C Worker identity changed across Runtime Server restart"
            );
            awaitProjectedSlot(
                    "slot-c-projected",
                    options,
                    runtime,
                    slotCWorker,
                    UNMATCHED_SLOT
            );
            Map<WorkerRef, String> completeWorld = new LinkedHashMap<>(
                    reconnected
            );
            completeWorld.putAll(slotCWorker);
            require(
                    state.workerIdsByCoordinate().equals(
                            coordinates(completeWorld)
                    ),
                    "Worker identities changed across Runtime Server restart"
            );
            awaitRestartWitness(
                    options,
                    lab,
                    runtime,
                    evidence,
                    state.propertyWitnessTaskId(),
                    state.propertyWitnessMessageId(),
                    expectedSlotCWorkerId
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-7", Map.of(), null),
                    options
            );
            require(
                    workload.offeredItemCount() == 700,
                    "State convergence workload did not offer 700 Items"
            );
            require(
                    workload.invalidInputCount() == 70,
                    "State convergence workload did not contain 70 invalid inputs"
            );
            require(
                    workload.offeredDelayItemCount() == 7,
                    "State convergence workload did not offer 7 delay Items"
            );
            require(
                    workload.offeredFailItemCount() == 7,
                    "State convergence workload did not offer 7 fail Items"
            );
            evidence.record("complete", "named-witnesses-converged", Map.of(
                    "workerCount", completeWorld.size(),
                    "offeredItemCount", workload.offeredItemCount(),
                    "invalidInputCount", workload.invalidInputCount(),
                    "offeredDelayItemCount", workload.offeredDelayItemCount(),
                    "offeredFailItemCount", workload.offeredFailItemCount(),
                    "witnessCount", workload.witnessMessageIds().size(),
                    "waveCount", 7
            ));
            evidence.writeSummary("succeeded", Map.of(
                    "workerCount", completeWorld.size(),
                    "offeredItemCount", workload.offeredItemCount(),
                    "invalidInputCount", workload.invalidInputCount(),
                    "offeredDelayItemCount", workload.offeredDelayItemCount(),
                    "offeredFailItemCount", workload.offeredFailItemCount(),
                    "convergedWitnessCount", workload.witnessMessageIds().size(),
                    "serverRestartObserved", true,
                    "workerIdentityStable", true
            ));
        } catch (RuntimeException error) {
            writeFailure(evidence, "after-server-restart", error);
            throw error;
        }
    }

    private static void requireExactWorld(WorkerLabControlClient lab) {
        List<WorkerSnapshot> workers = lab.workers();
        require(
                workers.size() == WorkerLabConvergenceSupport.WORKER_COUNT,
                "Convergence world must contain 1000 Workers"
        );
        Map<WorkerRef, WorkerSnapshot> inventory =
                WorkerLabConvergenceSupport.requireInventory(
                        lab,
                        CONVERGENCE_WORKERS
                );
        require(
                inventory.values().stream().allMatch(snapshot ->
                        "RUNNING".equals(snapshot.desiredState())
                                && "RUNNING".equals(snapshot.runtimeState())),
                "All convergence Workers must be running at baseline"
        );
    }

    private static void requireServerRestartWorld(WorkerLabControlClient lab) {
        List<WorkerSnapshot> workers = lab.workers();
        require(
                workers.size() == WorkerLabConvergenceSupport.WORKER_COUNT,
                "Convergence world must contain 1000 Workers"
        );
        Map<WorkerRef, WorkerSnapshot> inventory =
                WorkerLabConvergenceSupport.requireInventory(
                        lab,
                        CONVERGENCE_WORKERS
                );
        for (WorkerRef worker : CONVERGENCE_WORKERS) {
            WorkerSnapshot snapshot = inventory.get(worker);
            boolean expected = SLOT_C_WORKER.equals(worker)
                    ? isStopped(snapshot)
                    : "RUNNING".equals(snapshot.desiredState())
                    && "RUNNING".equals(snapshot.runtimeState());
            require(
                    expected,
                    "Only the slot C Worker may remain stopped after Server restart"
            );
        }
    }

    private static void requireStableIdentities(
            StateConvergencePhaseState state,
            Map<WorkerRef, String> observed
    ) {
        observed.forEach((worker, workerId) -> require(
                workerId.equals(
                        state.workerIdsByCoordinate().get(worker.coordinate())
                ),
                "Worker identity changed across Runtime Server restart"
        ));
    }

    private static void stopOnceAndAwait(
            String phase,
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            Map<WorkerRef, String> identities,
            List<WorkerRef> workers,
            ConvergenceEvidence evidence
    ) {
        stopLocally(phase, options, lab, workers, evidence);
        awaitUnavailable(
                phase,
                options,
                runtime,
                identities,
                workers,
                evidence,
                "disconnected"::equals
        );
    }

    private static void awaitUnavailable(
            String phase,
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            Map<WorkerRef, String> identities,
            List<WorkerRef> workers,
            ConvergenceEvidence evidence,
            Predicate<String> acceptedNetworkState
    ) {
        require(
                !workers.isEmpty()
                        && workers.size()
                        <= WorkerLabConvergenceSupport.WORKER_COUNT,
                "Unavailable observation requires 1..1000 Workers"
        );
        Map<WorkerRef, String> targetIdentities = new LinkedHashMap<>();
        for (WorkerRef worker : workers) {
            String workerId = identities.get(worker);
            require(workerId != null, "Missing baseline Worker identity");
            targetIdentities.put(worker, workerId);
        }
        List<String> workerIds = List.copyOf(targetIdentities.values());
        Map<String, String> networkStates = await(
                phase + "-network",
                options.maximumWait(),
                () -> runtime.observeNetwork(
                        options.endpointManagerId(),
                        workerIds
                ),
                states -> states.size() == workerIds.size()
                        && workerIds.stream().allMatch(workerId ->
                        acceptedNetworkState.test(states.get(workerId))),
                states -> WorkerLabConvergenceSupport.describeUnexpectedStates(
                        workerIds,
                        states,
                        acceptedNetworkState
                )
        );

        Map<String, List<String>> identitiesByGroup = new LinkedHashMap<>();
        targetIdentities.forEach((worker, workerId) -> identitiesByGroup
                .computeIfAbsent(worker.groupId(), ignored -> new ArrayList<>())
                .add(workerId));
        Map<String, String> schedulingStates = new LinkedHashMap<>();
        identitiesByGroup.forEach((groupId, groupWorkerIds) ->
                schedulingStates.putAll(await(
                        phase + "-scheduling-" + groupId,
                        options.maximumWait(),
                        () -> runtime.observeScheduling(groupId, groupWorkerIds),
                        states -> states.size() == groupWorkerIds.size()
                                && groupWorkerIds.stream().allMatch(workerId ->
                                WorkerLabConvergenceSupport
                                        .isUnavailableSchedulingState(
                                                states.get(workerId)
                                        )),
                        states -> WorkerLabConvergenceSupport
                                .describeUnexpectedStates(
                                        groupWorkerIds,
                                        states,
                                        WorkerLabConvergenceSupport
                                                ::isUnavailableSchedulingState
                                )
                ))
        );
        targetIdentities.forEach((worker, workerId) -> evidence.record(
                phase,
                "worker-unavailable",
                Map.of(
                        "workerGroupId", worker.groupId(),
                        "labWorkerKey", worker.labWorkerKey(),
                        "workerId", workerId,
                        "networkState", networkStates.get(workerId),
                        "schedulingState", schedulingStates.get(workerId)
                )
        ));
    }

    private static void stopLocally(
            String phase,
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            List<WorkerRef> workers,
            ConvergenceEvidence evidence
    ) {
        workers.forEach(worker -> {
            lab.stop(worker.groupId(), worker.labWorkerKey());
            evidence.record(phase, "stop-issued", Map.of(
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey()
            ));
        });
        for (WorkerRef worker : workers) {
            await(
                    phase + "-local-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    () -> lab.worker(worker.groupId(), worker.labWorkerKey()),
                    WorkerLabConvergenceSupport::isStopped
            );
        }
    }

    private static Map<WorkerRef, String> startOnceAndAwait(
            String phase,
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            List<WorkerRef> workers,
            ConvergenceEvidence evidence
    ) {
        Map<WorkerRef, String> connected = startOnceAndAwaitConnected(
                phase,
                options,
                lab,
                runtime,
                workers,
                evidence
        );
        awaitHot(phase + "-hot", options, runtime, connected);
        return connected;
    }

    private static Map<WorkerRef, String> startOnceAndAwaitConnected(
            String phase,
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            List<WorkerRef> workers,
            ConvergenceEvidence evidence
    ) {
        workers.forEach(worker -> {
            lab.start(worker.groupId(), worker.labWorkerKey());
            evidence.record(phase, "start-issued", Map.of(
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey()
            ));
        });
        Map<WorkerRef, String> connected = awaitConnected(
                phase + "-connected",
                options,
                workers,
                lab,
                runtime,
                evidence
        );
        return connected;
    }

    private static void mutatePropertiesOnce(
            String phase,
            WorkerLabControlClient lab,
            List<WorkerRef> workers,
            String slot,
            ConvergenceEvidence evidence
    ) {
        for (WorkerRef worker : workers) {
            WorkerSnapshot snapshot = lab.worker(
                    worker.groupId(),
                    worker.labWorkerKey()
            );
            require(isStopped(snapshot), "Properties may change only while stopped");
            Map<String, Object> changed = snapshot.requireWorkerProperties();
            changed.put(SLOT_PROPERTY, slot);
            lab.replaceProperties(worker.groupId(), worker.labWorkerKey(), changed);
            evidence.record(phase, "local-state-replaced", Map.of(
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey(),
                    "slot", slot
            ));
        }
    }

    private static void awaitProjectedSlot(
            String phase,
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            Map<WorkerRef, String> workers,
            String slot
    ) {
        for (WorkerRef worker : workers.keySet()) {
            await(
                    phase + "-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    () -> runtime.previewWorkers(worker.groupId())
                            .get(worker.labWorkerKey()),
                    view -> view != null
                            && slot.equals(
                            view.workerProperties().get(SLOT_PROPERTY))
            );
        }
    }

    private static void awaitHot(
            String phase,
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            Map<WorkerRef, String> workers
    ) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        workers.forEach((worker, workerId) -> byGroup
                .computeIfAbsent(worker.groupId(), ignored -> new ArrayList<>())
                .add(workerId));
        byGroup.forEach((groupId, workerIds) -> await(
                phase + "-" + groupId,
                options.maximumWait(),
                () -> runtime.observeScheduling(groupId, workerIds),
                states -> states.size() == workerIds.size()
                        && states.values().stream().allMatch(
                        WorkerLabConvergenceSupport::isHotSchedulingState),
                states -> WorkerLabConvergenceSupport.describeUnexpectedStates(
                        workerIds,
                        states,
                        WorkerLabConvergenceSupport::isHotSchedulingState
                )
        ));
    }

    private static String workerId(
            Map<WorkerRef, String> identities,
            WorkerRef worker
    ) {
        String workerId = identities.get(worker);
        require(workerId != null, "Missing Worker identity for Candidate Rule");
        return workerId;
    }

    private static void awaitWaveWitnesses(
            ConvergenceWorkload workload,
            List<Batch> wave,
            WorkerLabHarnessOptions options
    ) {
        for (Batch batch : wave) {
            workload.awaitWitness(batch, options.maximumWait());
        }
    }

    private static void awaitRestartWitness(
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence,
            String taskId,
            String messageId,
            String targetWorkerId
    ) {
        try {
            await(
                    "precomputed-property-witness",
                    options.maximumWait(),
                    () -> runtime.loadResultStatuses(
                            taskId,
                            List.of(messageId)
                    ),
                    states -> states.get(messageId) == CallStatus.SUCCEEDED
            );
        } catch (RuntimeException error) {
            recordRestartFailureSnapshot(
                    options,
                    lab,
                    runtime,
                    evidence,
                    taskId,
                    messageId,
                    targetWorkerId,
                    error
            );
            throw error;
        }
    }

    private static void recordRestartFailureSnapshot(
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence,
            String taskId,
            String messageId,
            String targetWorkerId,
            RuntimeException primaryFailure
    ) {
        try {
            Map<String, Object> facts = new LinkedHashMap<>();
            captureSnapshot(
                    facts,
                    "witnessStatus",
                    () -> {
                        CallStatus status = runtime.loadResultStatuses(
                                taskId,
                                List.of(messageId)
                        ).get(messageId);
                        return status == null ? "missing" : status.wireValue();
                    },
                    primaryFailure
            );
            captureSnapshot(
                    facts,
                    "taskScoreBand",
                    () -> runtime.previewTaskScoreBands(
                            List.of(taskId)
                    ).getOrDefault(taskId, "missing"),
                    primaryFailure
            );
            captureSnapshot(
                    facts,
                    "networkState",
                    () -> runtime.observeNetwork(
                            options.endpointManagerId(),
                            List.of(targetWorkerId)
                    ).getOrDefault(targetWorkerId, "missing"),
                    primaryFailure
            );
            captureSnapshot(
                    facts,
                    "schedulingState",
                    () -> runtime.observeScheduling(
                            SLOT_C_WORKER.groupId(),
                            List.of(targetWorkerId)
                    ).getOrDefault(targetWorkerId, "missing"),
                    primaryFailure
            );
            try {
                WorkerSnapshot local = lab.worker(
                        SLOT_C_WORKER.groupId(),
                        SLOT_C_WORKER.labWorkerKey()
                );
                facts.put("localDesiredState", local.desiredState());
                facts.put("localRuntimeState", local.runtimeState());
            } catch (RuntimeException snapshotFailure) {
                primaryFailure.addSuppressed(snapshotFailure);
                facts.put("localDesiredState", "unavailable");
                facts.put("localRuntimeState", "unavailable");
            }
            try {
                RuntimeApiClient.WorkerView view = runtime.previewWorkers(
                        SLOT_C_WORKER.groupId()
                ).get(SLOT_C_WORKER.labWorkerKey());
                facts.put(
                        "slotCProjected",
                        view != null && UNMATCHED_SLOT.equals(
                                view.workerProperties().get(SLOT_PROPERTY)
                        )
                );
            } catch (RuntimeException snapshotFailure) {
                primaryFailure.addSuppressed(snapshotFailure);
            }
            facts.put("targetWorkerId", targetWorkerId);
            evidence.record(
                    "server-restart",
                    "property-witness-timeout-snapshot",
                    facts
            );
        } catch (RuntimeException snapshotFailure) {
            primaryFailure.addSuppressed(snapshotFailure);
        }
    }

    private static void captureSnapshot(
            Map<String, Object> facts,
            String name,
            Supplier<String> observation,
            RuntimeException primaryFailure
    ) {
        try {
            facts.put(name, observation.get());
        } catch (RuntimeException snapshotFailure) {
            primaryFailure.addSuppressed(snapshotFailure);
            facts.put(name, "unavailable");
        }
    }

    private static Map<String, String> coordinates(
            Map<WorkerRef, String> workers
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        workers.forEach((worker, workerId) -> values.put(
                worker.coordinate(),
                workerId
        ));
        return Map.copyOf(values);
    }

    private static List<WorkerRef> joined(
            List<WorkerRef> first,
            List<WorkerRef> second
    ) {
        List<WorkerRef> values = new ArrayList<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    private static void writeFailure(
            ConvergenceEvidence evidence,
            String phase,
            RuntimeException error
    ) {
        try {
            evidence.writeSummary("failed", Map.of(
                    "phase", phase,
                    "failureKind", "proof-not-established",
                    "failure", safeMessage(error)
            ));
        } catch (IOException writeFailure) {
            error.addSuppressed(writeFailure);
        }
    }
}
