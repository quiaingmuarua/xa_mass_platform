package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CONTROLLED;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_EVENT;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_ONE;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_TWO;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_EVENT;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_ONE;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_TWO;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.await;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitConnected;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitNetworkState;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitTaskFinalResults;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitUnavailableScheduling;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.createTask;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.isUnavailableSchedulingState;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.numberEquals;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.require;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.safeMessage;

import com.xa.mass.integration.workerlab.RuntimeApiClient.ExportResult;
import com.xa.mass.integration.workerlab.RuntimeApiClient.WorkerView;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.TaskProof;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerStateConvergence {

    static final String LANE = "state-convergence";
    private static final long MUTATED_LAB_SLOT = 901L;

    private WorkerStateConvergence() {
    }

    static void execute(WorkerLabHarnessOptions options) throws Exception {
        ConvergenceEvidence evidence = ConvergenceEvidence.begin(
                options.proofId(),
                LANE,
                options.evidenceDirectory()
        );
        WorkerLabControlClient lab = options.labClient();
        RuntimeApiClient runtime = options.runtimeClient();
        RuntimeException failure = null;
        Map<String, Object> successFacts = Map.of();
        try {
            verifyStartupWorld(lab, evidence);
            successFacts = run(
                    options,
                    lab,
                    runtime,
                    evidence
            );
        } catch (RuntimeException error) {
            failure = error;
        }

        if (failure == null) {
            evidence.writeSummary("succeeded", successFacts);
            return;
        }
        try {
            evidence.writeSummary("failed", Map.of(
                    "failureKind", "proof-not-established",
                    "failure", safeMessage(failure)
            ));
        } catch (IOException writeFailure) {
            failure.addSuppressed(writeFailure);
        }
        throw failure;
    }

    private static Map<String, Object> run(
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence
    ) {
        long initialObservationStarted = System.nanoTime();
        Map<WorkerRef, String> workerIds = new LinkedHashMap<>(awaitConnected(
                "startup-stable-workers-connected",
                options,
                List.of(PHONE_ONE, PHONE_TWO, STRING_TWO),
                lab,
                runtime,
                evidence
        ));
        WorkerView scheduledWorkerView = await(
                "startup-scheduled-worker-identified",
                options.maximumWait(),
                () -> runtime.previewWorkers(STRING_GROUP)
                        .get(STRING_ONE.labWorkerKey()),
                view -> view != null
                        && view.workerId() != null
                        && !view.workerId().isBlank()
        );
        String scheduledWorkerId = scheduledWorkerView.workerId();
        workerIds.put(STRING_ONE, scheduledWorkerId);
        WorkerSnapshot scheduledWorker = lab.worker(
                STRING_ONE.groupId(),
                STRING_ONE.labWorkerKey()
        );
        evidence.record("startup", "scheduled-worker-identified", Map.of(
                "workerId", scheduledWorkerId,
                "desiredState", scheduledWorker.desiredState(),
                "runtimeState", scheduledWorker.runtimeState()
        ));
        evidence.record("startup", "initial-world-observed", Map.of(
                "workerCount", CONTROLLED.size(),
                "elapsedMillis", elapsedMillis(initialObservationStarted)
        ));

        long stopObservedAt = System.nanoTime();
        WorkerSnapshot startupStopped = await(
                "startup-scheduled-stop",
                options.maximumWait(),
                () -> lab.worker(
                        STRING_ONE.groupId(),
                        STRING_ONE.labWorkerKey()
                ),
                WorkerLabConvergenceSupport::isStopped
        );
        evidence.record(
                "startup-fault",
                "local-scheduled-stop-observed",
                Map.of(
                        "workerGroupId", STRING_ONE.groupId(),
                        "labWorkerKey", STRING_ONE.labWorkerKey(),
                        "desiredState", startupStopped.desiredState(),
                        "runtimeState", startupStopped.runtimeState()
                )
        );
        awaitNetworkState(
                "startup-scheduled-worker-disconnected",
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                scheduledWorkerId,
                "disconnected"
        );
        String unavailableState = awaitUnavailableScheduling(
                "startup-scheduled-worker-unavailable",
                options.maximumWait(),
                runtime,
                STRING_ONE,
                scheduledWorkerId
        );
        evidence.record("startup-fault", "worker-converged-unavailable", Map.of(
                "workerId", scheduledWorkerId,
                "networkState", "disconnected",
                "schedulingState", unavailableState,
                "elapsedMillis", elapsedMillis(stopObservedAt)
        ));

        recordMutation(
                "start",
                STRING_ONE,
                lab.start(STRING_ONE.groupId(), STRING_ONE.labWorkerKey()),
                evidence
        );
        Map<WorkerRef, String> restarted = awaitConnected(
                "explicit-worker-restart",
                options,
                List.of(STRING_ONE),
                lab,
                runtime,
                evidence
        );
        require(
                scheduledWorkerId.equals(restarted.get(STRING_ONE)),
                "Explicit restart changed the Server-owned workerId"
        );

        String propertyWorkerId = workerIds.get(STRING_TWO);
        stopAndObserve(
                STRING_TWO,
                propertyWorkerId,
                options,
                lab,
                runtime,
                evidence
        );
        Map<String, Object> changed = new LinkedHashMap<>(
                lab.worker(
                        STRING_TWO.groupId(),
                        STRING_TWO.labWorkerKey()
                ).requireWorkerProperties()
        );
        changed.put("labSlot", MUTATED_LAB_SLOT);
        WorkerSnapshot replaced = lab.replaceProperties(
                STRING_TWO.groupId(),
                STRING_TWO.labWorkerKey(),
                changed
        );
        require(
                numberEquals(
                        replaced.requireWorkerProperties().get("labSlot"),
                        MUTATED_LAB_SLOT
                ),
                "Lab did not expose the replaced local Properties"
        );
        recordMutation("replace-properties", STRING_TWO, replaced, evidence);
        var beforePrepare = runtime.previewWorkers(STRING_GROUP)
                .get(STRING_TWO.labWorkerKey());
        require(
                beforePrepare != null
                        && propertyWorkerId.equals(beforePrepare.workerId())
                        && !numberEquals(
                                beforePrepare.workerProperties().get("labSlot"),
                                MUTATED_LAB_SLOT
                        ),
                "Worker file replacement changed Runtime Properties before Prepare"
        );
        evidence.record(
                "properties",
                "runtime-snapshot-unchanged-before-prepare",
                Map.of("workerId", propertyWorkerId)
        );
        recordMutation(
                "start",
                STRING_TWO,
                lab.start(STRING_TWO.groupId(), STRING_TWO.labWorkerKey()),
                evidence
        );
        Map<WorkerRef, String> propertyRestart = awaitConnected(
                "property-worker-restarted",
                options,
                List.of(STRING_TWO),
                lab,
                runtime,
                evidence
        );
        require(
                propertyWorkerId.equals(propertyRestart.get(STRING_TWO)),
                "Properties refresh changed the Server-owned workerId"
        );
        await(
                "runtime-properties-refreshed",
                options.maximumWait(),
                () -> runtime.previewWorkers(STRING_GROUP)
                        .get(STRING_TWO.labWorkerKey()),
                view -> view != null
                        && propertyWorkerId.equals(view.workerId())
                        && numberEquals(
                                view.workerProperties().get("labSlot"),
                                MUTATED_LAB_SLOT
                        )
        );

        stopAndObserve(
                STRING_ONE,
                scheduledWorkerId,
                options,
                lab,
                runtime,
                evidence
        );
        stopAndObserve(
                STRING_TWO,
                propertyWorkerId,
                options,
                lab,
                runtime,
                evidence
        );
        List<String> stoppedStringIds = List.of(
                scheduledWorkerId,
                propertyWorkerId
        );
        Map<String, String> unavailableStates = await(
                "string-workers-unavailable-before-task",
                options.maximumWait(),
                () -> runtime.observeScheduling(
                        STRING_GROUP,
                        stoppedStringIds
                ),
                states -> stoppedStringIds.stream().allMatch(
                        id -> isUnavailableSchedulingState(states.get(id))
                )
        );
        evidence.record("task-isolation", "string-workers-unavailable", Map.of(
                "workerIds", stoppedStringIds,
                "schedulingStates", unavailableStates
        ));

        TaskProof parked = createTask(
                runtime,
                STRING_GROUP,
                Map.of("worker.labSlot", Map.of("$eq", MUTATED_LAB_SLOT)),
                STRING_EVENT,
                "value",
                List.of("worker-lab-string-1", "worker-lab-string-2"),
                20
        );
        ExportResult early = runtime.exportResults(
                parked.taskId(),
                Math.min(1_000, options.maximumWaitMillis())
        );
        require(
                !early.ready(),
                "Unavailable Group Task completed before recovery"
        );

        TaskProof independent = createTask(
                runtime,
                PHONE_GROUP,
                Map.of(),
                PHONE_EVENT,
                "rawNumber",
                List.of("+14155552671", "+442071838750"),
                20
        );
        awaitTaskFinalResults(
                "independent-group-task-completed",
                options.maximumWait(),
                runtime,
                independent
        );

        recordMutation(
                "start",
                STRING_TWO,
                lab.start(STRING_TWO.groupId(), STRING_TWO.labWorkerKey()),
                evidence
        );
        awaitConnected(
                "matching-worker-restarted",
                options,
                List.of(STRING_TWO),
                lab,
                runtime,
                evidence
        );
        awaitTaskFinalResults(
                "parked-task-completed",
                options.maximumWait(),
                runtime,
                parked
        );

        List<String> messageIds = new ArrayList<>();
        messageIds.addAll(independent.messageIds());
        messageIds.addAll(parked.messageIds());
        Collections.sort(messageIds);
        Map<String, String> identityEvidence = new LinkedHashMap<>();
        workerIds.forEach((worker, workerId) -> identityEvidence.put(
                worker.labWorkerKey(),
                workerId
        ));
        return Map.of(
                "controlledWorkerIds", identityEvidence,
                "taskIds", List.of(independent.taskId(), parked.taskId()),
                "messageIds", messageIds,
                "resultCount", messageIds.size(),
                "identityReuseObserved", true,
                "propertyRefreshObserved", true,
                "independentGroupTaskFinalized", true,
                "parkedTaskFinalizedAfterRecovery", true
        );
    }

    private static void verifyStartupWorld(
            WorkerLabControlClient lab,
            ConvergenceEvidence evidence
    ) {
        Map<WorkerRef, WorkerSnapshot> inventory =
                WorkerLabConvergenceSupport.requireInventory(lab, CONTROLLED);
        evidence.record("inventory", "required-inventory-observed", Map.of(
                "workerCount", inventory.size(),
                "controlledWorkerCount", CONTROLLED.size(),
                "runningWorkerCount", inventory.values().stream()
                        .filter(WorkerLabConvergenceSupport::isRunning)
                        .count()
        ));
    }

    private static void stopAndObserve(
            WorkerRef worker,
            String workerId,
            WorkerLabHarnessOptions options,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence
    ) {
        WorkerSnapshot accepted = lab.stop(
                worker.groupId(),
                worker.labWorkerKey()
        );
        recordMutation("stop", worker, accepted, evidence);
        await(
                "worker-stopped-" + worker.labWorkerKey(),
                options.maximumWait(),
                () -> lab.worker(
                        worker.groupId(),
                        worker.labWorkerKey()
                ),
                WorkerLabConvergenceSupport::isStopped
        );
        awaitNetworkState(
                "worker-disconnected-" + worker.labWorkerKey(),
                options.maximumWait(),
                runtime,
                options.endpointManagerId(),
                workerId,
                "disconnected"
        );
    }

    private static void recordMutation(
            String action,
            WorkerRef worker,
            WorkerSnapshot snapshot,
            ConvergenceEvidence evidence
    ) {
        evidence.record("mutation", "operation-accepted", Map.of(
                "operation", action,
                "workerGroupId", worker.groupId(),
                "labWorkerKey", worker.labWorkerKey(),
                "desiredState", snapshot.desiredState(),
                "runtimeState", snapshot.runtimeState()
        ));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
