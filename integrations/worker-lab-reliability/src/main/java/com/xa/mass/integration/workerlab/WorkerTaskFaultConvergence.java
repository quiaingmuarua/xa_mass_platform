package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CHECKPOINT_EVENT;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_ONE;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_TWO;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.await;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitConnected;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitNetworkState;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitTaskFinalResults;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitUnavailableScheduling;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.createTask;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.isStopped;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.numberEquals;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.require;

import com.xa.mass.integration.workerlab.RuntimeApiClient.ExportResult;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.TaskProof;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.CommandCheckpoint;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkerTaskFaultConvergence {

    static final String LANE = "task-fault-convergence";
    static final long TARGET_LAB_SLOT = 1L;

    private WorkerTaskFaultConvergence() {
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
            verifyArmWorld(lab);
            String targetWorkerId = awaitConnected(
                    "target-worker-connected",
                    options,
                    List.of(STRING_ONE),
                    lab,
                    runtime,
                    evidence
            ).get(STRING_ONE);
            await(
                    "target-worker-hot",
                    options.maximumWait(),
                    () -> runtime.observeScheduling(
                            STRING_GROUP,
                            List.of(targetWorkerId)
                    ).get(targetWorkerId),
                    WorkerLabConvergenceSupport::isHotSchedulingState
            );

            String token = "checkpoint-" + UUID.randomUUID();
            lab.armCommandCheckpoint(
                    STRING_ONE.groupId(),
                    STRING_ONE.labWorkerKey(),
                    token,
                    Math.min(120_000L, options.maximumWaitMillis())
            );
            checkpointArmed = true;
            TaskProof task = createTask(
                    runtime,
                    STRING_GROUP,
                    Map.of(
                            "worker.labSlot",
                            Map.of("$eq", TARGET_LAB_SLOT)
                    ),
                    CHECKPOINT_EVENT,
                    "checkpointToken",
                    List.of(token),
                    98
            );
            CommandCheckpoint entered = await(
                    "command-checkpoint-entered",
                    options.maximumWait(),
                    () -> lab.commandCheckpoint(
                            STRING_ONE.groupId(),
                            STRING_ONE.labWorkerKey()
                    ),
                    checkpoint -> "ENTERED".equals(checkpoint.state())
            );
            require(
                    entered.enteredAtEpochMillis() != null,
                    "Entered checkpoint has no timestamp"
            );
            String messageId = task.messageIds().iterator().next();
            TaskFaultState state = new TaskFaultState(
                    options.proofId(),
                    Instant.now(),
                    targetWorkerId,
                    task.taskId(),
                    messageId,
                    token,
                    TARGET_LAB_SLOT,
                    null
            );
            state.save(phaseStatePath);
            evidence.record("arm", "handler-entered-checkpoint", Map.of(
                    "workerId", targetWorkerId,
                    "taskId", task.taskId(),
                    "messageId", messageId,
                    "checkpointToken", token,
                    "enteredAtEpochMillis", entered.enteredAtEpochMillis()
            ));
        } catch (RuntimeException error) {
            if (checkpointArmed) {
                try {
                    lab.releaseCommandCheckpoint(
                            STRING_ONE.groupId(),
                            STRING_ONE.labWorkerKey()
                    );
                } catch (RuntimeException releaseFailure) {
                    error.addSuppressed(releaseFailure);
                }
            }
            evidence.writeSummary("failed", Map.of(
                    "phase", "arm",
                    "failureKind", "proof-not-established",
                    "failure", WorkerLabConvergenceSupport.safeMessage(error)
            ));
            throw error;
        }
    }

    static void observeDown(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = TaskFaultState.load(phaseStatePath);
        require(
                options.proofId().equals(state.proofId()),
                "Task-fault proofId changed between phases"
        );
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        RuntimeApiClient runtime = options.runtimeClient();
        awaitNetworkState(
                "crashed-worker-disconnected",
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                state.targetWorkerId(),
                "disconnected"
        );
        String schedulingState = awaitUnavailableScheduling(
                "crashed-worker-unavailable",
                options.maximumWait(),
                runtime,
                STRING_ONE,
                state.targetWorkerId()
        );
        evidence.record("host-down", "worker-converged-unavailable", Map.of(
                "workerId", state.targetWorkerId(),
                "networkState", "disconnected",
                "schedulingState", schedulingState,
                "taskId", state.taskId()
        ));
    }

    static void recover(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = TaskFaultState.load(phaseStatePath);
        require(
                options.proofId().equals(state.proofId()),
                "Task-fault proofId changed between phases"
        );
        ConvergenceEvidence evidence = ConvergenceEvidence.resume(
                options.proofId(),
                LANE,
                options.evidenceDirectory(),
                state.startedAt()
        );
        WorkerLabControlClient lab = options.labClient();
        RuntimeApiClient runtime = options.runtimeClient();
        String recoveredWorkerId = awaitConnected(
                "backup-worker-connected",
                options,
                List.of(STRING_TWO),
                lab,
                runtime,
                evidence
        ).get(STRING_TWO);
        await(
                "backup-properties-observed",
                options.maximumWait(),
                () -> runtime.previewWorkers(STRING_GROUP)
                        .get(STRING_TWO.labWorkerKey()),
                worker -> worker != null
                        && recoveredWorkerId.equals(worker.workerId())
                        && numberEquals(
                                worker.workerProperties().get("labSlot"),
                                state.labSlot()
                        )
        );
        awaitTaskFinalResults(
                "faulted-task-recovered",
                options.maximumWait(),
                runtime,
                new TaskProof(
                        state.taskId(),
                        java.util.Set.of(state.messageId()),
                        STRING_GROUP
                )
        );
        state.recoveredBy(recoveredWorkerId).save(phaseStatePath);
        evidence.record("recovery", "backup-worker-finalized-task", Map.of(
                "workerId", recoveredWorkerId,
                "taskId", state.taskId(),
                "messageId", state.messageId()
        ));
    }

    static void verifyFinality(
            WorkerLabHarnessOptions options,
            Path phaseStatePath
    ) throws Exception {
        TaskFaultState state = TaskFaultState.load(phaseStatePath);
        require(
                options.proofId().equals(state.proofId()),
                "Task-fault proofId changed between phases"
        );
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
        awaitNetworkState(
                "recovery-worker-disconnected-after-finality",
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                state.recoveredWorkerId(),
                "disconnected"
        );
        ExportResult exported = runtime.exportResults(
                state.taskId(),
                Math.min(2_000L, options.maximumWaitMillis())
        );
        require(exported.ready(), "Final Task Result is no longer exportable");
        require(
                exported.messageIds().equals(java.util.Set.of(
                        state.messageId()
                )),
                "Final Task Result identity changed after Worker disappeared"
        );
        evidence.record("finality", "result-remained-final", Map.of(
                "taskId", state.taskId(),
                "messageId", state.messageId(),
                "hostState", "offline"
        ));
        evidence.writeSummary("succeeded", Map.of(
                "targetWorkerId", state.targetWorkerId(),
                "recoveredWorkerId", state.recoveredWorkerId(),
                "taskId", state.taskId(),
                "messageIds", List.of(state.messageId()),
                "resultCount", 1,
                "executionFaultObserved", true,
                "finalityRetainedAfterWorkerLoss", true
        ));
    }

    private static void verifyArmWorld(WorkerLabControlClient lab) {
        Map<WorkerRef, WorkerSnapshot> inventory =
                WorkerLabConvergenceSupport.requireInventory(
                        lab,
                        List.of(STRING_ONE, STRING_TWO)
                );
        require(
                "RUNNING".equals(
                        inventory.get(STRING_ONE).desiredState()
                ),
                "Task-fault target Worker was not started"
        );
        inventory.forEach((worker, snapshot) -> {
            if (STRING_GROUP.equals(worker.groupId())
                    && !STRING_ONE.equals(worker)) {
                require(
                        isStopped(snapshot),
                        "Task-fault startup plan started another String Worker"
                );
            }
        });
        Map<String, Object> targetProperties = lab.worker(
                STRING_ONE.groupId(),
                STRING_ONE.labWorkerKey()
        ).requireWorkerProperties();
        require(
                numberEquals(targetProperties.get("labSlot"), TARGET_LAB_SLOT),
                "Task-fault target Worker does not own the target labSlot"
        );
    }
}
