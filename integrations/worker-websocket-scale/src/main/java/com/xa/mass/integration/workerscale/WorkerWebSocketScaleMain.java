package com.xa.mass.integration.workerscale;

import com.xa.mass.integration.workerscale.ScaleApiClient.LabWorker;
import com.xa.mass.integration.workerscale.ScaleEvidence.ScaleTopology;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.LoadedOperationResult;
import com.xa.mass.integration.workerscale.ScaleLoadedOperation.TaskProgress;
import com.xa.mass.integration.workerscale.ScaleOptions.Phase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public-API proof for one phase of the loaded Java WebSocket scale lane. */
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
            ScaleApiClient api = new ScaleApiClient(
                    new ScaleHttpClient(
                            options.labBaseUri(),
                            options.requestTimeout()
                    ),
                    new ScaleHttpClient(
                            options.serverBaseUri(),
                            options.requestTimeout()
                    )
            );
            ScaleTopology topology = loadAndVerifyTopology(options);
            boolean allRunning = options.phase() == Phase.INITIAL;
            Map<String, String> observedIdentityByLabKey = awaitIdentityInventory(
                    options,
                    api,
                    topology,
                    allRunning
            );
            Map<String, String> identityByLabKey = establishIdentityBaseline(
                    options,
                    topology,
                    observedIdentityByLabKey
            );

            int stopBatchCount = 0;
            int stableScans = 0;
            Convergence initialHeadroom = null;
            if (options.phase() == Phase.INITIAL) {
                List<String> allWorkerIds = List.copyOf(
                        identityByLabKey.values()
                );
                initialHeadroom = awaitConvergence(
                        options,
                        api,
                        allWorkerIds,
                        List.of(),
                        options.minimumInitialConverged(),
                        "initial-headroom"
                );
                stableScans = verifyStableHold(
                        options,
                        api,
                        allWorkerIds,
                        options.minimumInitialConverged()
                );
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
            }

            List<String> activeWorkerIds = workerIds(
                    identityByLabKey,
                    topology.retainedLabWorkerKeys()
            );
            List<String> stoppedWorkerIds = workerIds(
                    identityByLabKey,
                    topology.stoppedLabWorkerKeys()
            );
            Convergence activeConvergence = awaitConvergence(
                    options,
                    api,
                    activeWorkerIds,
                    stoppedWorkerIds,
                    options.minimumRetainedConverged(),
                    "retained-fleet"
            );
            LoadedOperationResult operation = ScaleLoadedOperation.run(
                    options,
                    api,
                    activeWorkerIds
            );
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
            summary.put("stoppedIdentities", stoppedWorkerIds.size());
            summary.put(
                    "allWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(identityByLabKey.values())
            );
            summary.put(
                    "retainedWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(activeWorkerIds)
            );
            summary.put(
                    "stoppedWorkerIdSetSha256",
                    ScaleEvidence.identityDigest(stoppedWorkerIds)
            );
            summary.put("batchStopRequestCount", stopBatchCount);
            summary.put("stableHoldScans", stableScans);
            if (initialHeadroom != null) {
                summary.put(
                        "initialHeadroomConnectedAndHot",
                        initialHeadroom.scan().activeConnectedAndHot()
                );
                summary.put(
                        "initialHeadroomConsecutiveScans",
                        initialHeadroom.consecutive()
                );
            }
            summary.put(
                    "consecutiveRetainedConvergedScans",
                    activeConvergence.consecutive()
            );
            summary.put(
                    "retainedConnectedAndHotWorkers",
                    activeConvergence.scan().activeConnectedAndHot()
            );
            summary.put(
                    "stoppedConnectedWorkers",
                    activeConvergence.scan().stoppedConnected()
            );
            summary.put(
                    "stoppedHotWorkers",
                    activeConvergence.scan().stoppedHot()
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
            summary.put("completedAtEpochMillis", System.currentTimeMillis());
            ScaleEvidence.writeSummary(options.summaryFile(), summary);
            LOG.log(
                    System.Logger.Level.INFO,
                    "Worker WebSocket scale phase {0} passed: prepared={1}, "
                            + "retained={2}, connectedAndHot={3}",
                    options.phase().wireValue(),
                    options.preparedWorkers(),
                    options.retainedWorkers(),
                    activeConvergence.scan().activeConnectedAndHot()
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
        Set<String> requiredIdentityKeys = allRunning
                ? expectedKeys
                : retained;
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
        if (options.phase() == Phase.INITIAL) {
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
                "phase", options.phase().wireValue(),
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
                    "phase", options.phase().wireValue(),
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
                        && latest.stoppedHot() == 0;
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

    private static int verifyStableHold(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> activeWorkerIds,
            int minimumConverged
    ) {
        if (options.stableHold().isZero()) {
            return 0;
        }
        long started = System.nanoTime();
        long holdNanos = options.stableHold().toNanos();
        int scans = 0;
        while (true) {
            Scan observed = scan(
                    options,
                    api,
                    activeWorkerIds,
                    List.of(),
                    "initial-stable-hold"
            );
            scans++;
            if (observed.activeConnectedAndHot() < minimumConverged
                    || observed.activeHotWithoutConnection() != 0) {
                throw new IllegalStateException(
                        "Initial Worker headroom fell below the stable threshold"
                );
            }
            long elapsed = System.nanoTime() - started;
            if (elapsed >= holdNanos) {
                return scans;
            }
            sleep(min(
                    options.scanInterval(),
                    Duration.ofNanos(holdNanos - elapsed)
            ));
        }
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
                stopped.hot()
        );
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("atEpochMillis", System.currentTimeMillis());
        evidence.put("phase", options.phase().wireValue());
        evidence.put("event", "projection-scan");
        evidence.put("stage", stage);
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
                boolean isHot = isHot(scheduling.get(workerId));
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
                hotWithoutConnection
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
        result.put("phase", options.phase().wireValue());
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
                "phase", options.phase().wireValue(),
                "event", "observation-failed",
                "stage", stage,
                "failure", safeMessage(failure)
        ));
    }

    private static boolean isHot(String value) {
        return "held-hot".equals(value) || "hot-score-overdue".equals(value);
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
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
                    "phase", options.phase().wireValue(),
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
            int hotWithoutConnection
    ) {
    }

    private record Scan(
            int activeConnected,
            int activeHot,
            int activeConnectedAndHot,
            int activeHotWithoutConnection,
            int stoppedConnected,
            int stoppedHot
    ) {
    }

    private record Convergence(Scan scan, int consecutive) {
    }
}
