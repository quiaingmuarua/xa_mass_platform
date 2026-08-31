package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CONTROLLED;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_EVENT;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.PHONE_GROUP;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.STRING_EVENT;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.await;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitHotScheduling;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitNetworkState;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitTaskFinalResults;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.awaitUnavailableScheduling;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.createTask;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.isRunning;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.isStopped;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.require;
import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.safeMessage;

import com.xa.mass.integration.workerlab.RuntimeApiClient.ExportResult;
import com.xa.mass.integration.workerlab.RuntimeApiClient.WorkerView;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.TaskProof;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.integration.workerlab.WorkerLabControlClient.WorkerSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class WorkerConvergenceCampaign {

    static final String LANE = "convergence-campaign";
    static final long DEFAULT_SEED = 20_260_831L;
    static final int DEFAULT_ROUNDS = 20;
    static final long SLOT_A = 701L;
    static final long SLOT_B = 702L;

    private static final long SCHEDULED_STOP_DELAY_MILLIS = 250L;
    private static final int TASK_MAX_RETRY_TIMES = 20;

    private WorkerConvergenceCampaign() {
    }

    static void execute(
            WorkerLabHarnessOptions options,
            long seed,
            int rounds
    ) throws Exception {
        if (rounds < 1 || rounds > 100) {
            throw new IllegalArgumentException("rounds must be in 1..100");
        }
        ConvergenceEvidence evidence = ConvergenceEvidence.begin(
                options.proofId(),
                LANE,
                options.evidenceDirectory()
        );
        try {
            Map<String, Object> facts = run(
                    options,
                    seed,
                    rounds,
                    options.labClient(),
                    options.runtimeClient(),
                    evidence
            );
            evidence.writeSummary("succeeded", facts);
        } catch (RuntimeException failure) {
            try {
                evidence.writeSummary("failed", Map.of(
                        "seed", seed,
                        "rounds", rounds,
                        "failureKind", "proof-not-established",
                        "failure", safeMessage(failure)
                ));
            } catch (IOException writeFailure) {
                failure.addSuppressed(writeFailure);
            }
            throw failure;
        }
    }

    static List<CampaignAction> plannedActions(long seed, int rounds) {
        if (rounds < 1 || rounds > 100) {
            throw new IllegalArgumentException("rounds must be in 1..100");
        }
        Random random = new Random(seed);
        List<CampaignAction> actions = new ArrayList<>(rounds);
        ActionType[] types = ActionType.values();
        for (int round = 1; round <= rounds; round++) {
            actions.add(new CampaignAction(
                    round,
                    types[random.nextInt(types.length)],
                    CONTROLLED.get(random.nextInt(CONTROLLED.size())),
                    random.nextBoolean() ? SLOT_A : SLOT_B
            ));
        }
        return List.copyOf(actions);
    }

    private static Map<String, Object> run(
            WorkerLabHarnessOptions options,
            long seed,
            int rounds,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence
    ) {
        Map<WorkerRef, WorkerSnapshot> baseline =
                WorkerLabConvergenceSupport.requireInventory(lab, CONTROLLED);
        Map<WorkerRef, String> observedWorkerIds = new LinkedHashMap<>();
        for (WorkerRef worker : CONTROLLED) {
            observeIdentity(worker, baseline.get(worker), observedWorkerIds);
        }
        evidence.record("baseline", "local-world-observed", Map.of(
                "workerCount", baseline.size(),
                "controlledWorkerCount", CONTROLLED.size(),
                "knownIdentityCount", observedWorkerIds.size()
        ));

        List<CampaignTask> tasks = new ArrayList<>();
        int establishedActions = 0;
        int unestablishedActions = 0;
        List<CampaignAction> actions = plannedActions(seed, rounds);
        for (CampaignAction action : actions) {
            evidence.record("campaign", "operation-attempted", action.evidence());
            if (executeAction(
                    action,
                    lab,
                    runtime,
                    evidence,
                    observedWorkerIds,
                    tasks
            )) {
                establishedActions++;
            } else {
                unestablishedActions++;
            }
        }
        require(
                establishedActions > 0,
                "Campaign did not establish any operation"
        );

        Map<WorkerRef, WorkerSnapshot> finalLocal = await(
                "campaign-local-mutations-settled",
                options.maximumWait(),
                () -> WorkerLabConvergenceSupport.requireInventory(
                        lab,
                        CONTROLLED
                ),
                inventory -> CONTROLLED.stream().allMatch(worker ->
                        inventory.get(worker).scheduledStopAtEpochMillis() == null
                )
        );
        FinalWorld finalWorld = observeFinalWorld(
                options,
                runtime,
                evidence,
                finalLocal,
                observedWorkerIds
        );
        require(
                finalWorld.convergenceAssertions() > 0,
                "Campaign ended without an observable convergence assertion"
        );

        TaskSummary taskSummary = observeTasks(
                options,
                runtime,
                evidence,
                tasks,
                finalWorld.serviceableSlots()
        );
        return Map.ofEntries(
                Map.entry("seed", seed),
                Map.entry("rounds", rounds),
                Map.entry("attemptedActionCount", actions.size()),
                Map.entry("establishedActionCount", establishedActions),
                Map.entry("unestablishedActionCount", unestablishedActions),
                Map.entry("stableRunningWorkerCount", finalWorld.runningWorkers()),
                Map.entry("stableStoppedWorkerCount", finalWorld.stoppedWorkers()),
                Map.entry("unresolvedLocalWorkerCount", finalWorld.unresolvedWorkers()),
                Map.entry("evaluatedWorkerCount",
                        finalWorld.runningWorkers() + finalWorld.stoppedWorkers()),
                Map.entry("convergenceAssertionCount", finalWorld.convergenceAssertions()),
                Map.entry("submittedTaskCount", tasks.size()),
                Map.entry("finalTaskCount", taskSummary.finalTasks()),
                Map.entry("pendingWithoutCapacityCount", taskSummary.pendingWithoutCapacity()),
                Map.entry("partialWorldEvaluation",
                        finalWorld.unresolvedWorkers() > 0)
        );
    }

    private static boolean executeAction(
            CampaignAction action,
            WorkerLabControlClient lab,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence,
            Map<WorkerRef, String> observedWorkerIds,
            List<CampaignTask> tasks
    ) {
        WorkerRef worker = action.worker();
        try {
            Map<String, Object> facts = new LinkedHashMap<>(
                    action.evidence()
            );
            WorkerSnapshot snapshot = null;
            switch (action.type()) {
                case START -> snapshot = lab.start(
                            worker.groupId(),
                            worker.labWorkerKey()
                    );
                case STOP -> snapshot = lab.stop(
                            worker.groupId(),
                            worker.labWorkerKey()
                    );
                case SCHEDULE_STOP -> snapshot = lab.scheduleStop(
                            worker.groupId(),
                            worker.labWorkerKey(),
                            SCHEDULED_STOP_DELAY_MILLIS
                    );
                case CANCEL_SCHEDULED_STOP -> {
                    lab.cancelScheduledStop(
                            worker.groupId(),
                            worker.labWorkerKey()
                    );
                    facts.put("response", "accepted");
                }
                case REPROPERTY -> {
                    Map<String, Object> properties = lab.worker(
                            worker.groupId(),
                            worker.labWorkerKey()
                    ).requireWorkerProperties();
                    properties.put("labSlot", action.labSlot());
                    snapshot = lab.replaceProperties(
                            worker.groupId(),
                            worker.labWorkerKey(),
                            properties
                    );
                }
                case CREATE_TASK -> {
                    TaskProof task = createSlotTask(
                            runtime,
                            worker.groupId(),
                            action.labSlot(),
                            "campaign-round-" + action.round()
                    );
                    tasks.add(new CampaignTask(task, action.labSlot()));
                    facts.put("taskId", task.taskId());
                }
            }
            if (snapshot != null) {
                observeIdentity(worker, snapshot, observedWorkerIds);
                facts.put("desiredState", snapshot.desiredState());
                facts.put("runtimeState", snapshot.runtimeState());
            }
            evidence.record("campaign", "operation-established", facts);
            return true;
        } catch (RuntimeException failure) {
            Map<String, Object> facts = new LinkedHashMap<>(action.evidence());
            facts.put("failure", safeMessage(failure));
            evidence.record("campaign", "operation-not-established", facts);
            return false;
        }
    }

    private static FinalWorld observeFinalWorld(
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence,
            Map<WorkerRef, WorkerSnapshot> finalLocal,
            Map<WorkerRef, String> observedWorkerIds
    ) {
        Map<String, Set<Long>> serviceableSlots = new LinkedHashMap<>();
        int running = 0;
        int stopped = 0;
        int unresolved = 0;
        int assertions = 0;
        for (WorkerRef worker : CONTROLLED) {
            WorkerSnapshot snapshot = finalLocal.get(worker);
            observeIdentity(worker, snapshot, observedWorkerIds);
            evidence.record("final-world", "local-worker-observed", Map.of(
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey(),
                    "desiredState", snapshot.desiredState(),
                    "runtimeState", snapshot.runtimeState()
            ));
            String workerId = observedWorkerIds.get(worker);
            if (workerId == null) {
                unresolved++;
                continue;
            }
            if (isStopped(snapshot)) {
                stopped++;
                String network = awaitNetworkState(
                        "campaign-final-disconnected-"
                                + worker.labWorkerKey(),
                        options.maximumWait(),
                        runtime,
                        options.endpointManagerId(),
                        workerId,
                        "disconnected"
                );
                String scheduling = awaitUnavailableScheduling(
                        "campaign-final-unavailable-"
                                + worker.labWorkerKey(),
                        options.maximumWait(),
                        runtime,
                        worker,
                        workerId
                );
                assertions += 2;
                evidence.record("final-world", "stopped-worker-converged", Map.of(
                        "workerId", workerId,
                        "networkState", network,
                        "schedulingState", scheduling
                ));
                continue;
            }
            if (!isRunning(snapshot)) {
                unresolved++;
                continue;
            }
            running++;
            String network = awaitNetworkState(
                    "campaign-final-connected-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    runtime,
                    options.endpointManagerId(),
                    workerId,
                    "connected"
            );
            String scheduling = awaitHotScheduling(
                    "campaign-final-hot-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    runtime,
                    worker,
                    workerId
            );
            assertions += 2;
            WorkerView view = await(
                    "campaign-final-preview-" + worker.labWorkerKey(),
                    options.maximumWait(),
                    () -> runtime.previewWorkers(worker.groupId())
                            .get(worker.labWorkerKey()),
                    value -> value != null && workerId.equals(value.workerId())
            );
            Object slotValue = view.workerProperties().get("labSlot");
            if (slotValue instanceof Number slot) {
                serviceableSlots.computeIfAbsent(
                        worker.groupId(),
                        ignored -> new LinkedHashSet<>()
                ).add(slot.longValue());
            }
            evidence.record("final-world", "running-worker-converged", Map.of(
                    "workerId", workerId,
                    "networkState", network,
                    "schedulingState", scheduling
            ));
        }
        Map<String, Set<Long>> immutableSlots = new LinkedHashMap<>();
        serviceableSlots.forEach((group, slots) -> immutableSlots.put(
                group,
                Set.copyOf(slots)
        ));
        return new FinalWorld(
                Map.copyOf(immutableSlots),
                running,
                stopped,
                unresolved,
                assertions
        );
    }

    private static TaskSummary observeTasks(
            WorkerLabHarnessOptions options,
            RuntimeApiClient runtime,
            ConvergenceEvidence evidence,
            List<CampaignTask> tasks,
            Map<String, Set<Long>> serviceableSlots
    ) {
        int finalTasks = 0;
        int pendingWithoutCapacity = 0;
        for (CampaignTask task : tasks) {
            ExportResult observed = runtime.exportResults(
                    task.proof().taskId(),
                    Math.min(1_000L, options.maximumWaitMillis())
            );
            if (!observed.ready()
                    && serviceableSlots.getOrDefault(
                            task.proof().workerGroupId(),
                            Set.of()
                    ).contains(task.labSlot())) {
                awaitTaskFinalResults(
                        "campaign-task-" + task.proof().taskId(),
                        options.maximumWait(),
                        runtime,
                        task.proof()
                );
                finalTasks++;
                evidence.record("task", "task-converged-with-capacity", Map.of(
                        "taskId", task.proof().taskId(),
                        "workerGroupId", task.proof().workerGroupId(),
                        "labSlot", task.labSlot()
                ));
                continue;
            }
            if (observed.ready()) {
                require(
                        task.proof().messageIds().equals(observed.messageIds()),
                        "Campaign Task Result identities changed"
                );
                finalTasks++;
                evidence.record("task", "task-final-observed", Map.of(
                        "taskId", task.proof().taskId(),
                        "workerGroupId", task.proof().workerGroupId(),
                        "labSlot", task.labSlot()
                ));
            } else {
                pendingWithoutCapacity++;
                evidence.record("task", "task-pending-without-final-capacity", Map.of(
                        "taskId", task.proof().taskId(),
                        "workerGroupId", task.proof().workerGroupId(),
                        "labSlot", task.labSlot()
                ));
            }
        }
        return new TaskSummary(finalTasks, pendingWithoutCapacity);
    }

    private static TaskProof createSlotTask(
            RuntimeApiClient runtime,
            String groupId,
            long slot,
            String value
    ) {
        boolean phone = PHONE_GROUP.equals(groupId);
        return createTask(
                runtime,
                groupId,
                Map.of("worker.labSlot", Map.of("$eq", slot)),
                phone ? PHONE_EVENT : STRING_EVENT,
                phone ? "rawNumber" : "value",
                List.of(phone ? "+14155552671" : value),
                TASK_MAX_RETRY_TIMES
        );
    }

    private static void observeIdentity(
            WorkerRef worker,
            WorkerSnapshot snapshot,
            Map<WorkerRef, String> observedWorkerIds
    ) {
        if (snapshot == null
                || snapshot.workerId() == null
                || snapshot.workerId().isBlank()) {
            return;
        }
        observedWorkerIds.put(worker, snapshot.workerId());
    }

    enum ActionType {
        START,
        STOP,
        SCHEDULE_STOP,
        CANCEL_SCHEDULED_STOP,
        REPROPERTY,
        CREATE_TASK
    }

    record CampaignAction(
            int round,
            ActionType type,
            WorkerRef worker,
            long labSlot
    ) {
        Map<String, Object> evidence() {
            return Map.of(
                    "round", round,
                    "operation", type.name(),
                    "workerGroupId", worker.groupId(),
                    "labWorkerKey", worker.labWorkerKey(),
                    "labSlot", labSlot
            );
        }
    }

    private record CampaignTask(TaskProof proof, long labSlot) {
    }

    private record FinalWorld(
            Map<String, Set<Long>> serviceableSlots,
            int runningWorkers,
            int stoppedWorkers,
            int unresolvedWorkers,
            int convergenceAssertions
    ) {
    }

    private record TaskSummary(
            int finalTasks,
            int pendingWithoutCapacity
    ) {
    }
}
