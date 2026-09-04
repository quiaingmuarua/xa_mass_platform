package com.xa.mass.integration.workerscale;

import com.xa.mass.integration.workerscale.ScaleApiClient.LabWorker;
import com.xa.mass.integration.workerscale.ScaleEvidence.ScaleTopology;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.LoadedOperation;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.LoadedOperationResult;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.MutationCheckpoint;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.RecoverySnapshot;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.TaskProgress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public-API proof for one stage of the loaded Java WebSocket scale lane. */
public final class WorkerWebSocketScaleMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerWebSocketScaleMain.class.getName()
    );
    private static final int OBSERVATION_CHUNK_SIZE = 100;
    private static final int STOP_BATCH_SIZE = 100;
    private static final int REQUIRED_CONSECUTIVE_SCANS = 3;

    private WorkerWebSocketScaleMain() {
    }

    public static void main(String[] arguments) {
        ScaleOptions options = ScaleOptions.parse(arguments);
        long startedAt = System.currentTimeMillis();
        Map<String, Object> summary = baseSummary(options, startedAt);
        try {
            ScaleApiClient api = createApi(options);
            ScaleTopology topology = loadAndVerifyTopology(options);
            boolean initial = options.stage().isInitialContraction();
            Map<String, String> observedIdentityByLabKey = awaitIdentityInventory(
                    options,
                    api,
                    topology,
                    initial
            );
            Map<String, String> identityByLabKey = establishIdentityBaseline(
                    options,
                    topology,
                    observedIdentityByLabKey
            );

            List<String> activeWorkerIds = workerIds(
                    identityByLabKey,
                    topology.retainedLabWorkerKeys()
            );
            List<String> stoppedWorkerIds = workerIds(
                    identityByLabKey,
                    topology.stoppedLabWorkerKeys()
            );

            StableWindow initialHeadroom = null;
            Convergence activeConvergence = null;
            ScaleProcessGate.GateResume gateResume = null;
            if (initial) {
                initialHeadroom = awaitStableWindow(
                        options,
                        api,
                        List.copyOf(identityByLabKey.values()),
                        options.minimumInitialConverged()
                );
                gateResume = new ScaleProcessGate(options).awaitInitialHeadroom(
                        initialHeadroom.scan().activeConnectedAndHot(),
                        initialHeadroom.qualifyingScans(),
                        initialHeadroom.stableMillis()
                );
            } else {
                activeConvergence = awaitConvergence(
                        options,
                        api,
                        activeWorkerIds,
                        stoppedWorkerIds,
                        options.minimumRetainedConverged(),
                        "pre-work-convergence"
                );
            }

            LoadedOperation loadedOperation = ScaleLoadedOperation.start(
                    options,
                    api,
                    activeWorkerIds
            );
            MutationCheckpoint mutation = loadedOperation
                    .awaitMutationCheckpoint(api);

            int stopBatchCount = 0;
            if (initial) {
                stopBatchCount = stopExcessWorkers(options, api, topology);
                observedIdentityByLabKey = awaitIdentityInventory(
                        options,
                        api,
                        topology,
                        false
                );
                verifyObservedIdentities(
                        identityByLabKey,
                        observedIdentityByLabKey,
                        topology.retainedLabWorkerKeys()
                );
            } else {
                gateResume = new ScaleProcessGate(options).awaitServerMutation(
                        mutation.taskCount(),
                        mutation.succeededItems(),
                        mutation.unresolvedItems()
                );
                api = createApi(options);
            }

            RecoverySnapshot recovery = loadedOperation.observeAfterMutation(
                    api,
                    options.stage().isHardRestart()
            );
            if (!initial) {
                loadedOperation.awaitRetainedConnectionsAfterServerRestart(api);
            }
            LoadedOperationResult operation = loadedOperation.awaitCompletion(
                    api,
                    recovery,
                    options.stage().isHardRestart()
            );

            observedIdentityByLabKey = awaitIdentityInventory(
                    options,
                    api,
                    topology,
                    false
            );
            verifyObservedIdentities(
                    identityByLabKey,
                    observedIdentityByLabKey,
                    topology.retainedLabWorkerKeys()
            );
            List<String> observedActiveWorkerIds = workerIds(
                    observedIdentityByLabKey,
                    topology.retainedLabWorkerKeys()
            );
            List<String> observedAndRetainedBaselineWorkerIds =
                    new ArrayList<>(observedActiveWorkerIds);
            observedAndRetainedBaselineWorkerIds.addAll(stoppedWorkerIds);
            Convergence postWorkConvergence = awaitConvergence(
                    options,
                    api,
                    activeWorkerIds,
                    stoppedWorkerIds,
                    options.minimumRetainedConverged(),
                    "post-work-convergence"
            );

            summary.put("status", "passed");
            summary.put("preparedIdentities", identityByLabKey.size());
            summary.put("retainedIdentities", activeWorkerIds.size());
            summary.put(
                    "observedRetainedIdentities",
                    observedActiveWorkerIds.size()
            );
            summary.put("stoppedIdentities", stoppedWorkerIds.size());
            summary.put(
                    "allWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(
                            observedAndRetainedBaselineWorkerIds
                    )
            );
            summary.put(
                    "retainedWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(observedActiveWorkerIds)
            );
            summary.put(
                    "stoppedWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(stoppedWorkerIds)
            );
            summary.put("batchStopRequestCount", stopBatchCount);
            if (initialHeadroom != null) {
                summary.put(
                        "initialHeadroomConnectedAndHot",
                        initialHeadroom.scan().activeConnectedAndHot()
                );
                summary.put(
                        "initialHeadroomQualifyingScans",
                        initialHeadroom.qualifyingScans()
                );
                summary.put(
                        "initialHeadroomStableMillis",
                        initialHeadroom.stableMillis()
                );
            }
            if (activeConvergence != null) {
                summary.put(
                        "preWorkConnectedAndHot",
                        activeConvergence.scan().activeConnectedAndHot()
                );
            }
            summary.put("mutationCheckpointTaskCount", mutation.taskCount());
            summary.put(
                    "mutationCheckpointSucceededItems",
                    mutation.succeededItems()
            );
            summary.put(
                    "mutationCheckpointUnresolvedItems",
                    mutation.unresolvedItems()
            );
            summary.put(
                    "mutationCheckpointElapsedMillis",
                    mutation.elapsedMillis()
            );
            summary.put("gateWaitMillis", gateResume.waitMillis());
            summary.put(
                    "recoverySnapshotSucceededItems",
                    recovery.succeededItems()
            );
            summary.put(
                    "recoverySnapshotUnresolvedItems",
                    recovery.unresolvedItems()
            );
            summary.put(
                    "postRecoveryProgress",
                    operation.postRecoveryProgress()
            );
            summary.put("activeTaskCount", ScaleLoadedOperation.TASK_COUNT);
            summary.put(
                    "maximumCandidateWorkersPerTask",
                    ScaleLoadedOperation.MAXIMUM_CANDIDATE_WORKERS
            );
            summary.put(
                    "offeredItemsPerTask",
                    options.workloadItemsPerTask()
            );
            summary.put(
                    "totalOfferedItems",
                    Math.multiplyExact(
                            ScaleLoadedOperation.TASK_COUNT,
                            options.workloadItemsPerTask()
                    )
            );
            summary.put("appendBatchCount", operation.appendBatchCount());
            summary.put(
                    "succeededItemCount",
                    operation.tasks().stream()
                            .mapToInt(TaskProgress::succeeded)
                            .sum()
            );
            summary.put("tasks", operation.tasks().stream()
                    .map(WorkerWebSocketScaleMain::taskSummary)
                    .toList());
            summary.put(
                    "maximumGlobalNoProgressMillis",
                    operation.maximumNoProgressMillis()
            );
            summary.put(
                    "minimumConnectedDuringWork",
                    operation.minimumConnected()
            );
            summary.put(
                    "postWorkConnectedAndHot",
                    postWorkConvergence.scan().activeConnectedAndHot()
            );
            summary.put(
                    "postWorkStoppedConnected",
                    postWorkConvergence.scan().stoppedConnected()
            );
            summary.put(
                    "postWorkStoppedHot",
                    postWorkConvergence.scan().stoppedHot()
            );
            summary.put(
                    "postWorkStoppedMissing",
                    postWorkConvergence.scan().stoppedMissing()
            );
            long completedAt = System.currentTimeMillis();
            summary.put(
                    "restartAndRecoveryMillis",
                    initial ? 0 : Math.max(
                            0,
                            completedAt - gateResume.resumedAtEpochMillis()
                    )
            );
            summary.put("completedAtEpochMillis", completedAt);
            ScaleEvidence.writeSummary(options.summaryFile(), summary);
            LOG.log(
                    System.Logger.Level.INFO,
                    "Worker WebSocket scale stage {0} passed: prepared={1}, "
                            + "retained={2}, connectedAndHot={3}",
                    options.stage().wireValue(),
                    options.preparedWorkers(),
                    options.retainedWorkers(),
                    postWorkConvergence.scan().activeConnectedAndHot()
            );
        } catch (RuntimeException | Error failure) {
            summary.put("status", "failed");
            summary.put("failureKind", "proof-not-established");
            summary.put("failure", safeMessage(failure));
            summary.put("completedAtEpochMillis", System.currentTimeMillis());
            try {
                ScaleEvidence.writeSummary(options.summaryFile(), summary);
            } catch (RuntimeException evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        }
    }

    private static ScaleApiClient createApi(ScaleOptions options) {
        return new ScaleApiClient(
                new ScaleHttpClient(
                        options.labBaseUri(),
                        options.requestTimeout()
                ),
                new ScaleHttpClient(
                        options.serverBaseUri(),
                        options.requestTimeout()
                )
        );
    }

    private static ScaleTopology loadAndVerifyTopology(ScaleOptions options) {
        ScaleTopology topology = ScaleEvidence.readTopology(
                options.topologyFile(),
                options.workerGroupId()
        );
        if (topology.retainedLabWorkerKeys().size()
                != options.retainedWorkers()) {
            throw new IllegalStateException(
                    "Private topology retained Worker count changed"
            );
        }
        if (topology.retainedLabWorkerKeys().size()
                + topology.stoppedLabWorkerKeys().size()
                != options.preparedWorkers()) {
            throw new IllegalStateException(
                    "Private topology prepared Worker count changed"
            );
        }
        return topology;
    }

    private static Map<String, String> awaitIdentityInventory(
            ScaleOptions options,
            ScaleApiClient api,
            ScaleTopology topology,
            boolean allRunning
    ) {
        long deadline = deadline(options.maximumConvergenceWait());
        RuntimeException latestFailure = null;
        do {
            try {
                InventoryObservation observation = observeInventory(
                        options,
                        topology,
                        api.labWorkers(),
                        allRunning
                );
                ScaleEvidence.appendTimeline(
                        options.timelineFile(),
                        observation.timeline(options)
                );
                if (observation.complete()) {
                    return observation.workerIdsByLabWorkerKey();
                }
            } catch (RuntimeException error) {
                latestFailure = error;
                appendFailure(options, "identity-inventory", error);
            }
            sleep(Duration.ofSeconds(1));
        } while (System.nanoTime() < deadline);
        IllegalStateException timeout = new IllegalStateException(
                "Timed out waiting for the exact Worker identity inventory"
        );
        if (latestFailure != null) {
            timeout.addSuppressed(latestFailure);
        }
        throw timeout;
    }

    private static InventoryObservation observeInventory(
            ScaleOptions options,
            ScaleTopology topology,
            List<LabWorker> workers,
            boolean allRunning
    ) {
        Set<String> retained = Set.copyOf(topology.retainedLabWorkerKeys());
        Set<String> stopped = Set.copyOf(topology.stoppedLabWorkerKeys());
        Set<String> expectedKeys = new LinkedHashSet<>(retained);
        expectedKeys.addAll(stopped);
        Map<String, LabWorker> byLabKey = new LinkedHashMap<>();
        for (LabWorker worker : workers) {
            if (!options.workerGroupId().equals(worker.workerGroupId())) {
                throw new IllegalStateException(
                        "Scale Lab contains an unexpected WorkerGroup"
                );
            }
            if (!expectedKeys.contains(worker.labWorkerKey())) {
                throw new IllegalStateException(
                        "Scale Lab contains an unexpected Worker coordinate"
                );
            }
            if (byLabKey.putIfAbsent(worker.labWorkerKey(), worker) != null) {
                throw new IllegalStateException(
                        "Scale Lab contains a duplicate Worker coordinate"
                );
            }
        }
        Map<String, String> identities = new LinkedHashMap<>();
        Set<String> uniqueWorkerIds = new HashSet<>();
        Set<String> requiredIdentityKeys = allRunning ? expectedKeys : retained;
        int running = 0;
        int stoppedCount = 0;
        boolean expectedStates = byLabKey.keySet().equals(expectedKeys);
        for (String labWorkerKey : expectedKeys) {
            LabWorker worker = byLabKey.get(labWorkerKey);
            if (worker == null) {
                continue;
            }
            boolean shouldRun = allRunning || retained.contains(labWorkerKey);
            String expectedState = shouldRun ? "RUNNING" : "STOPPED";
            if (!expectedState.equals(worker.desiredState())
                    || !expectedState.equals(worker.runtimeState())) {
                expectedStates = false;
            }
            if ("RUNNING".equals(worker.desiredState())
                    && "RUNNING".equals(worker.runtimeState())) {
                running++;
            }
            if ("STOPPED".equals(worker.desiredState())
                    && "STOPPED".equals(worker.runtimeState())) {
                stoppedCount++;
            }
            if (worker.workerId() != null && !worker.workerId().isBlank()) {
                if (!uniqueWorkerIds.add(worker.workerId())) {
                    throw new IllegalStateException(
                            "Scale Lab contains duplicate Server-issued workerIds"
                    );
                }
                identities.put(labWorkerKey, worker.workerId());
            }
        }
        boolean complete = byLabKey.size() == options.preparedWorkers()
                && identities.keySet().containsAll(requiredIdentityKeys)
                && expectedStates;
        return new InventoryObservation(
                byLabKey.size(),
                running,
                stoppedCount,
                identities,
                complete
        );
    }

    private static Map<String, String> establishIdentityBaseline(
            ScaleOptions options,
            ScaleTopology topology,
            Map<String, String> observedIdentityByLabKey
    ) {
        if (options.stage().isInitialContraction()) {
            if (observedIdentityByLabKey.size() != options.preparedWorkers()) {
                throw new IllegalStateException(
                        "Initial Lab inventory did not expose every workerId"
                );
            }
            ScaleEvidence.writeBaseline(
                    options.baselineFile(),
                    options.workerGroupId(),
                    observedIdentityByLabKey
            );
            appendIdentityEvidence(options, observedIdentityByLabKey);
            return observedIdentityByLabKey;
        }
        Map<String, String> baseline = ScaleEvidence.readBaseline(
                options.baselineFile(),
                options.workerGroupId()
        );
        Set<String> topologyKeys = new LinkedHashSet<>(
                topology.retainedLabWorkerKeys()
        );
        topologyKeys.addAll(topology.stoppedLabWorkerKeys());
        if (baseline.size() != options.preparedWorkers()
                || !baseline.keySet().equals(topologyKeys)) {
            throw new IllegalStateException(
                    "Identity baseline does not match the private topology"
            );
        }
        verifyObservedIdentities(
                baseline,
                observedIdentityByLabKey,
                topology.retainedLabWorkerKeys()
        );
        appendIdentityEvidence(options, baseline);
        return baseline;
    }

    private static void verifyObservedIdentities(
            Map<String, String> baseline,
            Map<String, String> observed,
            List<String> requiredLabWorkerKeys
    ) {
        for (String labWorkerKey : requiredLabWorkerKeys) {
            if (!baseline.get(labWorkerKey).equals(observed.get(labWorkerKey))) {
                throw new IllegalStateException(
                        "Active Worker identity changed for " + labWorkerKey
                );
            }
        }
        for (Map.Entry<String, String> entry : observed.entrySet()) {
            if (!entry.getValue().equals(baseline.get(entry.getKey()))) {
                throw new IllegalStateException(
                        "Observed Worker identity changed for " + entry.getKey()
                );
            }
        }
    }

    private static void appendIdentityEvidence(
            ScaleOptions options,
            Map<String, String> identityByLabKey
    ) {
        ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                "atEpochMillis", System.currentTimeMillis(),
                "stage", options.stage().wireValue(),
                "event", "identity-set-established",
                "workerCount", identityByLabKey.size(),
                "workerIdSetSha256",
                ScaleEvidence.identityDigest(identityByLabKey.values())
        ));
    }

    private static int stopExcessWorkers(
            ScaleOptions options,
            ScaleApiClient api,
            ScaleTopology topology
    ) {
        int batches = 0;
        List<String> stopped = topology.stoppedLabWorkerKeys();
        for (List<String> batch : stopBatches(stopped)) {
            api.stopWorkers(options.workerGroupId(), batch);
            batches++;
            ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                    "atEpochMillis", System.currentTimeMillis(),
                    "stage", options.stage().wireValue(),
                    "event", "lab-stop-batch-accepted",
                    "batchOrdinal", batches,
                    "acceptedCount", batch.size()
            ));
        }
        return batches;
    }

    static List<List<String>> stopBatches(List<String> labWorkerKeys) {
        List<List<String>> result = new ArrayList<>();
        for (int offset = 0;
                offset < labWorkerKeys.size();
                offset += STOP_BATCH_SIZE) {
            result.add(List.copyOf(labWorkerKeys.subList(
                    offset,
                    Math.min(offset + STOP_BATCH_SIZE, labWorkerKeys.size())
            )));
        }
        return List.copyOf(result);
    }

    private static List<String> workerIds(
            Map<String, String> identityByLabKey,
            List<String> labWorkerKeys
    ) {
        List<String> result = new ArrayList<>();
        for (String labWorkerKey : labWorkerKeys) {
            String workerId = identityByLabKey.get(labWorkerKey);
            if (workerId == null) {
                throw new IllegalStateException(
                        "Worker identity is missing for " + labWorkerKey
                );
            }
            result.add(workerId);
        }
        return List.copyOf(result);
    }

    private static Convergence awaitConvergence(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> activeWorkerIds,
            List<String> stoppedWorkerIds,
            int minimumConverged,
            String stage
    ) {
        long deadline = deadline(options.maximumConvergenceWait());
        int consecutive = 0;
        RuntimeException latestFailure = null;
        Scan latest = null;
        do {
            try {
                latest = scan(
                        options,
                        api,
                        activeWorkerIds,
                        stoppedWorkerIds,
                        stage
                );
                boolean converged = latest.activeConnectedAndHot()
                        >= minimumConverged
                        && latest.activeHotWithoutConnection() == 0
                        && latest.stoppedConnected() == 0
                        && latest.stoppedHot() == 0
                        && latest.stoppedMissing() == 0;
                consecutive = converged ? consecutive + 1 : 0;
                if (consecutive >= REQUIRED_CONSECUTIVE_SCANS) {
                    return new Convergence(latest, consecutive);
                }
            } catch (RuntimeException error) {
                latestFailure = error;
                consecutive = 0;
                appendFailure(options, stage, error);
            }
            sleep(options.scanInterval());
        } while (System.nanoTime() < deadline);
        IllegalStateException timeout = new IllegalStateException(
                "Worker projections did not converge for " + stage
                        + " (latest=" + latest + ")"
        );
        if (latestFailure != null) {
            timeout.addSuppressed(latestFailure);
        }
        throw timeout;
    }

    private static StableWindow awaitStableWindow(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> activeWorkerIds,
            int minimumConverged
    ) {
        long deadline = deadline(options.maximumConvergenceWait());
        long holdNanos = options.stableHold().toNanos();
        long stableSince = -1;
        int scans = 0;
        Scan latest = null;
        RuntimeException latestFailure = null;
        do {
            try {
                latest = scan(
                        options,
                        api,
                        activeWorkerIds,
                        List.of(),
                        "initial-headroom-window"
                );
                boolean qualifies = latest.activeConnectedAndHot()
                        >= minimumConverged
                        && latest.activeHotWithoutConnection() == 0;
                long now = System.nanoTime();
                if (qualifies) {
                    if (stableSince < 0) {
                        stableSince = now;
                        scans = 1;
                    } else {
                        scans++;
                    }
                    long stableNanos = now - stableSince;
                    if (scans >= REQUIRED_CONSECUTIVE_SCANS
                            && stableNanos >= holdNanos) {
                        return new StableWindow(
                                latest,
                                scans,
                                Duration.ofNanos(stableNanos).toMillis()
                        );
                    }
                } else {
                    stableSince = -1;
                    scans = 0;
                }
            } catch (RuntimeException error) {
                latestFailure = error;
                stableSince = -1;
                scans = 0;
                appendFailure(options, "initial-headroom-window", error);
            }
            sleep(options.scanInterval());
        } while (System.nanoTime() < deadline);
        IllegalStateException timeout = new IllegalStateException(
                "Initial Worker headroom did not sustain its stable window "
                        + "(latest=" + latest + ")"
        );
        if (latestFailure != null) {
            timeout.addSuppressed(latestFailure);
        }
        throw timeout;
    }

    private static Scan scan(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> activeWorkerIds,
            List<String> stoppedWorkerIds,
            String stage
    ) {
        WorkerStates active = observeStates(options, api, activeWorkerIds);
        WorkerStates stopped = observeStates(options, api, stoppedWorkerIds);
        Scan scan = new Scan(
                active.connected(),
                active.hot(),
                active.connectedAndHot(),
                active.hotWithoutConnection(),
                stopped.connected(),
                stopped.hot(),
                stopped.missing()
        );
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("atEpochMillis", System.currentTimeMillis());
        evidence.put("stage", options.stage().wireValue());
        evidence.put("event", "projection-scan");
        evidence.put("checkpoint", stage);
        evidence.put("activeWorkers", activeWorkerIds.size());
        evidence.put("activeConnected", active.connected());
        evidence.put("activeHot", active.hot());
        evidence.put("activeConnectedAndHot", active.connectedAndHot());
        evidence.put(
                "activeHotWithoutConnection",
                active.hotWithoutConnection()
        );
        evidence.put("stoppedWorkers", stoppedWorkerIds.size());
        evidence.put("stoppedConnected", stopped.connected());
        evidence.put("stoppedHot", stopped.hot());
        evidence.put("stoppedMissing", stopped.missing());
        ScaleEvidence.appendTimeline(options.timelineFile(), evidence);
        return scan;
    }

    private static WorkerStates observeStates(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> workerIds
    ) {
        int connected = 0;
        int hot = 0;
        int connectedAndHot = 0;
        int hotWithoutConnection = 0;
        int missing = 0;
        for (int offset = 0;
                offset < workerIds.size();
                offset += OBSERVATION_CHUNK_SIZE) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + OBSERVATION_CHUNK_SIZE, workerIds.size())
            );
            Map<String, String> network = api.observeNetwork(
                    options.endpointManagerId(),
                    chunk
            );
            Map<String, String> scheduling = api.observeScheduling(
                    options.workerGroupId(),
                    chunk
            );
            for (String workerId : chunk) {
                boolean isConnected = "connected".equals(network.get(workerId));
                String schedulingState = scheduling.get(workerId);
                boolean isHot = isHot(schedulingState);
                if ("missing".equals(schedulingState)) {
                    missing++;
                }
                if (isConnected) {
                    connected++;
                }
                if (isHot) {
                    hot++;
                }
                if (isConnected && isHot) {
                    connectedAndHot++;
                } else if (isHot) {
                    hotWithoutConnection++;
                }
            }
        }
        return new WorkerStates(
                connected,
                hot,
                connectedAndHot,
                hotWithoutConnection,
                missing
        );
    }

    private static Map<String, Object> taskSummary(TaskProgress task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", task.label());
        result.put("taskId", task.taskId());
        result.put("succeededCount", task.succeeded());
        result.put("firstSuccessElapsedMillis", task.firstSuccessElapsedMillis());
        result.put("completionElapsedMillis", task.completionElapsedMillis());
        result.put("exported", task.exported());
        return result;
    }

    private static Map<String, Object> baseSummary(
            ScaleOptions options,
            long startedAt
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proofId", options.proofId());
        result.put("lane", "worker-websocket-scale");
        result.put("stage", options.stage().wireValue());
        result.put("preparedWorkers", options.preparedWorkers());
        result.put("retainedWorkers", options.retainedWorkers());
        result.put(
                "minimumInitialConnectedAndHot",
                options.minimumInitialConverged()
        );
        result.put(
                "minimumRetainedConnectedAndHot",
                options.minimumRetainedConverged()
        );
        result.put("startedAtEpochMillis", startedAt);
        return result;
    }

    private static void appendFailure(
            ScaleOptions options,
            String stage,
            Throwable failure
    ) {
        ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                "atEpochMillis", System.currentTimeMillis(),
                "stage", options.stage().wireValue(),
                "event", "observation-failed",
                "checkpoint", stage,
                "failure", safeMessage(failure)
        ));
    }

    private static boolean isHot(String value) {
        return "held-hot".equals(value) || "hot-score-overdue".equals(value);
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scale proof was interrupted", error);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record InventoryObservation(
            int observedWorkers,
            int runningWorkers,
            int stoppedWorkers,
            Map<String, String> workerIdsByLabWorkerKey,
            boolean complete
    ) {

        Map<String, Object> timeline(ScaleOptions options) {
            return Map.of(
                    "atEpochMillis", System.currentTimeMillis(),
                    "stage", options.stage().wireValue(),
                    "event", "identity-inventory-observed",
                    "observedWorkers", observedWorkers,
                    "runningWorkers", runningWorkers,
                    "stoppedWorkers", stoppedWorkers,
                    "workersWithIdentity", workerIdsByLabWorkerKey.size(),
                    "expectedWorkers", options.preparedWorkers()
            );
        }
    }

    private record WorkerStates(
            int connected,
            int hot,
            int connectedAndHot,
            int hotWithoutConnection,
            int missing
    ) {
    }

    private record Scan(
            int activeConnected,
            int activeHot,
            int activeConnectedAndHot,
            int activeHotWithoutConnection,
            int stoppedConnected,
            int stoppedHot,
            int stoppedMissing
    ) {
    }

    private record Convergence(Scan scan, int consecutive) {
    }

    private record StableWindow(
            Scan scan,
            int qualifyingScans,
            long stableMillis
    ) {
    }
}
