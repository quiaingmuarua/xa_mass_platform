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
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final WorkerRef MUTATED_PHONE_WORKER = PHONE_WORKERS.get(5);
    private static final WorkerRef MUTATED_STRING_WORKER = STRING_WORKERS.get(5);
    private static final WorkerRef SLOT_C_WORKER = STRING_WORKERS.get(7);

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

            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    options.proofId()
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-1", Map.of(), null),
                    options
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
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-2", Map.of(), null),
                    options
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
            Map<String, Map<String, Object>> slotRules = Map.of(
                    PHONE_GROUP, slotRule(
                            workerId(mutatedIds, MUTATED_PHONE_WORKER),
                            MUTATED_SLOT
                    ),
                    STRING_GROUP, slotRule(
                            workerId(mutatedIds, MUTATED_STRING_WORKER),
                            MUTATED_SLOT
                    )
            );
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-3", slotRules, null),
                    options
            );

            stopOnceAndAwait(
                    "string-group-outage",
                    options,
                    lab,
                    runtime,
                    identities,
                    STRING_WORKERS,
                    evidence
            );
            List<Batch> outageWave = workload.submitWave(
                    "wave-4",
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
            startOnceAndAwait(
                    "string-group-recovery",
                    options,
                    lab,
                    runtime,
                    STRING_WORKERS,
                    evidence
            );
            workload.awaitWitness(stringOutage, options.maximumWait());

            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-5", Map.of(), null),
                    options
            );

            List<Batch> restartWave = workload.submitWave(
                    "wave-6",
                    Map.of(STRING_GROUP, slotRule(
                            workerId(identities, SLOT_C_WORKER),
                            UNMATCHED_SLOT
                    )),
                    null
            );
            Batch phoneBeforeRestart = workload.requireBatch(
                    restartWave,
                    PHONE_GROUP
            );
            Batch stringBeforeRestart = workload.requireBatch(
                    restartWave,
                    STRING_GROUP
            );
            workload.awaitWitness(phoneBeforeRestart, options.maximumWait());
            require(
                    workload.immediateWitnessStatus(stringBeforeRestart)
                            == CallStatus.NOT_OBSERVED,
                    "Unmatched String witness items:call status was not NOT_OBSERVED "
                            + "before slot C existed"
            );
            require(
                    !workload.witnessObserved(stringBeforeRestart),
                    "Unmatched String witness already had a successful Result before "
                            + "slot C existed"
            );
            new StateConvergencePhaseState(
                    options.proofId(),
                    Instant.now(),
                    coordinates(identities),
                    workload.batches()
            ).save(phaseStatePath);
            evidence.record("server-restart", "work-submitted-before-restart", Map.of(
                    "offeredItemCount", workload.offeredItemCount(),
                    "unmatchedWitnessMessageId",
                    stringBeforeRestart.witnessMessageId(),
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
            requireExactWorld(lab);
            Map<WorkerRef, String> reconnected = awaitConnected(
                    "server-restarted-connected",
                    options,
                    CONVERGENCE_WORKERS,
                    lab,
                    runtime,
                    evidence
            );
            require(
                    state.workerIdsByCoordinate().equals(coordinates(reconnected)),
                    "Worker identities changed across Runtime Server restart"
            );
            awaitHot("server-restarted-hot", options, runtime, reconnected);

            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    options.proofId(),
                    state.batches()
            );
            stopOnceAndAwait(
                    "slot-c-stop",
                    options,
                    lab,
                    runtime,
                    reconnected,
                    List.of(SLOT_C_WORKER),
                    evidence
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
            awaitProjectedSlot(
                    "slot-c-projected",
                    options,
                    runtime,
                    slotCWorker,
                    UNMATCHED_SLOT
            );
            workload.awaitWitness(
                    workload.requireBatch(state.batches(), STRING_GROUP, "wave-6"),
                    options.maximumWait()
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
                    "workerCount", reconnected.size(),
                    "offeredItemCount", workload.offeredItemCount(),
                    "invalidInputCount", workload.invalidInputCount(),
                    "offeredDelayItemCount", workload.offeredDelayItemCount(),
                    "offeredFailItemCount", workload.offeredFailItemCount(),
                    "witnessCount", workload.witnessMessageIds().size(),
                    "waveCount", 7
            ));
            evidence.writeSummary("succeeded", Map.of(
                    "workerCount", reconnected.size(),
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
        require(workers.size() == 100, "Convergence world must contain 100 Workers");
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
        for (WorkerRef worker : workers) {
            String workerId = identities.get(worker);
            require(workerId != null, "Missing baseline Worker identity");
            WorkerLabConvergenceSupport.awaitNetworkState(
                    phase + "-network-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    runtime,
                    options.endpointManagerId(),
                    workerId,
                    "disconnected"
            );
            String scheduling = WorkerLabConvergenceSupport.awaitUnavailableScheduling(
                    phase + "-scheduling-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    runtime,
                    worker,
                    workerId
            );
            evidence.record(phase, "worker-unavailable", Map.of(
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey(),
                    "workerId", workerId,
                    "networkState", "disconnected",
                    "schedulingState", scheduling
            ));
        }
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
        awaitHot(phase + "-hot", options, runtime, connected);
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
                        WorkerLabConvergenceSupport::isHotSchedulingState)
        ));
    }

    private static Map<String, Object> slotRule(
            String workerId,
            String slot
    ) {
        return Map.of(
                "workerId", Map.of("$eq", workerId),
                MATCH_PROPERTY, Map.of("$eq", slot)
        );
    }

    private static String workerId(
            Map<WorkerRef, String> identities,
            WorkerRef worker
    ) {
        String workerId = identities.get(worker);
        require(workerId != null, "Missing Worker identity for allocation rule");
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
