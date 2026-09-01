package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CONVERGENCE_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_WORKERS;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.await;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitConnected;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.numberEquals;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.require;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.safeMessage;

import com.xa.mass.integration.workerlab.ConvergenceWorkload.Batch;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.CommandCheckpoint;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkerTaskFaultConvergence {

    static final String LANE = "in-flight-loss-convergence";
    static final long TARGET_LAB_SLOT = 1L;
    static final WorkerRef TARGET = STRING_WORKERS.get(0);
    static final WorkerRef BACKUP = STRING_WORKERS.get(1);

    private WorkerTaskFaultConvergence() {
    }

    private static String requireWorkerId(
            Map<WorkerRef, String> identities,
            WorkerRef worker
    ) {
        String workerId = identities.get(worker);
        require(workerId != null, "Missing Worker identity for fault rule");
        return workerId;
    }

    static void arm(
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
        boolean checkpointArmed = false;
        try {
            requireBaselineWorld(lab);
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

            String token = "checkpoint-" + UUID.randomUUID();
            lab.armCommandCheckpoint(
                    TARGET.groupId(),
                    TARGET.labWorkerKey(),
                    token,
                    Math.min(120_000L, options.maximumWaitMillis())
            );
            checkpointArmed = true;
            Map<String, Map<String, Object>> targetRule = Map.of(
                    STRING_GROUP,
                    Map.of(
                            "workerId", Map.of("$in", List.of(
                                    requireWorkerId(identities, TARGET),
                                    requireWorkerId(identities, BACKUP)
                            )),
                            "worker.labSlot", Map.of("$eq", TARGET_LAB_SLOT)
                    )
            );
            List<Batch> faultWave = workload.submitWave(
                    "wave-2",
                    targetRule,
                    new ConvergenceWorkload.Checkpoint(token)
            );
            Batch checkpointBatch = workload.requireBatch(
                    faultWave,
                    STRING_GROUP
            );
            CommandCheckpoint entered = await(
                    "command-checkpoint-entered",
                    options.maximumWait(),
                    () -> lab.commandCheckpoint(
                            TARGET.groupId(),
                            TARGET.labWorkerKey()
                    ),
                    checkpoint -> "ENTERED".equals(checkpoint.state())
            );
            require(
                    entered.enteredAtEpochMillis() != null,
                    "Entered checkpoint has no timestamp"
            );
            new TaskFaultState(
                    options.proofId(),
                    Instant.now(),
                    coordinates(identities),
                    TARGET.coordinate(),
                    identities.get(TARGET),
                    BACKUP.coordinate(),
                    token,
                    checkpointBatch.witnessMessageId(),
                    TARGET_LAB_SLOT,
                    workload.batches(),
                    null
            ).save(phaseStatePath);
            evidence.record("arm", "handler-entered-checkpoint", Map.of(
                    "workerId", identities.get(TARGET),
                    "workerGroupId", TARGET.groupId(),
                    "labWorkerKey", TARGET.labWorkerKey(),
                    "checkpointToken", token,
                    "enteredAtEpochMillis", entered.enteredAtEpochMillis(),
                    "offeredItemCount", workload.offeredItemCount(),
                    "checkpointMessageId", checkpointBatch.witnessMessageId()
            ));
        } catch (RuntimeException error) {
            if (checkpointArmed) {
                try {
                    lab.releaseCommandCheckpoint(
                            TARGET.groupId(),
                            TARGET.labWorkerKey()
                    );
                } catch (RuntimeException releaseFailure) {
                    error.addSuppressed(releaseFailure);
                }
            }
            writeFailure(evidence, "arm", error);
            throw error;
        }
    }

    static void observeDown(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = requireState(options, phaseStatePath);
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        RuntimeApiClient runtime = options.runtimeClient();
        try {
            awaitAllUnavailable(options, runtime, state.workerIdsByCoordinate());
            evidence.record("host-down", "worker-world-unavailable", Map.of(
                    "workerCount", state.workerIdsByCoordinate().size(),
                    "targetWorkerId", state.targetWorkerId()
            ));
        } catch (RuntimeException error) {
            writeFailure(evidence, "host-down", error);
            throw error;
        }
    }

    static void recover(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = requireState(options, phaseStatePath);
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        WorkerLabControlClient lab = options.labClient();
        RuntimeApiClient runtime = options.runtimeClient();
        try {
            await(
                    "target-remained-stopped",
                    options.maximumWait(),
                    () -> lab.worker(TARGET.groupId(), TARGET.labWorkerKey()),
                    WorkerLabConvergenceSupport::isStopped
            );
            List<WorkerRef> active = workersWithoutTarget();
            Map<WorkerRef, String> identities = awaitConnected(
                    "backup-world-connected",
                    options,
                    active,
                    lab,
                    runtime,
                    evidence
            );
            awaitHot("backup-world-hot", options, runtime, identities);
            String backupWorkerId = identities.get(BACKUP);
            require(backupWorkerId != null, "Backup Worker was not started");
            await(
                    "backup-properties-observed",
                    options.maximumWait(),
                    () -> runtime.previewWorkers(STRING_GROUP)
                            .get(BACKUP.labWorkerKey()),
                    worker -> worker != null
                            && backupWorkerId.equals(worker.workerId())
                            && numberEquals(
                            worker.workerProperties().get("labSlot"),
                            state.labSlot()
                    )
            );

            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    options.proofId(),
                    state.batches()
            );
            Batch checkpointBatch = workload.batches().stream()
                    .filter(batch -> batch.witnessMessageId().equals(
                            state.checkpointMessageId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Checkpoint witness batch is missing"
                    ));
            workload.awaitWitness(checkpointBatch, options.maximumWait());
            awaitWaveWitnesses(
                    workload,
                    workload.submitWave("wave-3", Map.of(), null),
                    options
            );
            require(
                    workload.offeredItemCount() == 300,
                    "Task-fault workload did not offer 300 Items"
            );
            require(
                    workload.invalidInputCount() == 30,
                    "Task-fault workload did not contain 30 invalid inputs"
            );
            require(
                    workload.offeredDelayItemCount() == 3,
                    "Task-fault workload did not offer 3 delay Items"
            );
            require(
                    workload.offeredFailItemCount() == 3,
                    "Task-fault workload did not offer 3 fail Items"
            );
            state.recoveredBy(
                    backupWorkerId,
                    workload.batches()
            ).save(phaseStatePath);
            evidence.record("recovery", "backup-world-completed-work", Map.of(
                    "backupWorkerId", backupWorkerId,
                    "offeredItemCount", workload.offeredItemCount(),
                    "invalidInputCount", workload.invalidInputCount(),
                    "offeredDelayItemCount", workload.offeredDelayItemCount(),
                    "offeredFailItemCount", workload.offeredFailItemCount(),
                    "checkpointMessageId", state.checkpointMessageId(),
                    "activeWorkerCount", active.size()
            ));
        } catch (RuntimeException error) {
            writeFailure(evidence, "recovery", error);
            throw error;
        }
    }

    static void verifyFinality(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = requireState(options, phaseStatePath);
        require(
                state.recoveredWorkerId() != null,
                "Task-fault recovery phase has not completed"
        );
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        RuntimeApiClient runtime = options.runtimeClient();
        try {
            WorkerLabConvergenceSupport.awaitNetworkState(
                    "recovery-worker-disconnected-after-finality",
                    options.maximumWait(),
                    runtime,
                    options.endpointManagerId(),
                    state.recoveredWorkerId(),
                    "disconnected"
            );
            require(
                    runtime.loadResultStatuses(
                            RuntimeApiClient.managedTaskId(STRING_GROUP),
                            List.of(state.checkpointMessageId())
                    ).get(state.checkpointMessageId())
                            == RuntimeApiClient.CallStatus.SUCCEEDED,
                    "Checkpoint witness disappeared after Worker loss"
            );
            evidence.record("finality", "results-remained-final", Map.of(
                    "checkpointMessageId", state.checkpointMessageId(),
                    "hostState", "offline"
            ));
            evidence.writeSummary("succeeded", Map.of(
                    "targetWorkerId", state.targetWorkerId(),
                    "recoveredWorkerId", state.recoveredWorkerId(),
                    "offeredItemCount", 300,
                    "invalidInputCount", 30,
                    "offeredDelayItemCount", 3,
                    "offeredFailItemCount", 3,
                    "convergedWitnessCount", 5,
                    "checkpointMessageId", state.checkpointMessageId(),
                    "executionFaultObserved", true,
                    "finalityRetainedAfterWorkerLoss", true
            ));
        } catch (RuntimeException error) {
            writeFailure(evidence, "finality", error);
            throw error;
        }
    }

    private static TaskFaultState requireState(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) {
        TaskFaultState state = TaskFaultState.load(phaseStatePath);
        require(
                options.proofId().equals(state.proofId()),
                "Task-fault proofId changed between phases"
        );
        return state;
    }

    private static void requireBaselineWorld(WorkerLabControlClient lab) {
        List<WorkerSnapshot> workers = lab.workers();
        require(workers.size() == 100, "Task-fault world must contain 100 Workers");
        Map<WorkerRef, WorkerSnapshot> inventory =
                WorkerLabConvergenceSupport.requireInventory(
                        lab,
                        CONVERGENCE_WORKERS
                );
        require(
                inventory.values().stream().allMatch(snapshot ->
                        "RUNNING".equals(snapshot.desiredState())
                                && "RUNNING".equals(snapshot.runtimeState())),
                "All task-fault Workers must be running at baseline"
        );
        require(
                numberEquals(
                        lab.worker(TARGET.groupId(), TARGET.labWorkerKey())
                                .requireWorkerProperties()
                                .get("labSlot"),
                        TARGET_LAB_SLOT
                ),
                "Task-fault target does not own labSlot 1"
        );
    }

    private static void awaitAllUnavailable(
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            Map<String, String> workerIdsByCoordinate
    ) {
        List<String> allIds = List.copyOf(workerIdsByCoordinate.values());
        await(
                "host-down-network",
                options.maximumWait(),
                () -> runtime.observeNetwork(options.endpointManagerId(), allIds),
                states -> states.size() == allIds.size()
                        && states.values().stream().allMatch("disconnected"::equals)
        );
        awaitGroupUnavailable(options, runtime, PHONE_GROUP, PHONE_WORKERS,
                workerIdsByCoordinate);
        awaitGroupUnavailable(options, runtime, STRING_GROUP, STRING_WORKERS,
                workerIdsByCoordinate);
    }

    private static void awaitGroupUnavailable(
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            String groupId,
            List<WorkerRef> workers,
            Map<String, String> workerIdsByCoordinate
    ) {
        List<String> workerIds = workers.stream()
                .map(worker -> workerIdsByCoordinate.get(worker.coordinate()))
                .toList();
        await(
                "host-down-scheduling-" + groupId,
                options.maximumWait(),
                () -> runtime.observeScheduling(groupId, workerIds),
                states -> states.size() == workerIds.size()
                        && states.values().stream().allMatch(
                        WorkerLabConvergenceSupport::isUnavailableSchedulingState),
                states -> WorkerLabConvergenceSupport.describeUnexpectedStates(
                        workerIds,
                        states,
                        WorkerLabConvergenceSupport::isUnavailableSchedulingState
                )
        );
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

    private static List<WorkerRef> workersWithoutTarget() {
        return CONVERGENCE_WORKERS.stream()
                .filter(worker -> !TARGET.equals(worker))
                .toList();
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
