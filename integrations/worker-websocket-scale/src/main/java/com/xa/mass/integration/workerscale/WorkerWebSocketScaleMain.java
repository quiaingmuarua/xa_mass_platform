package com.xa.mass.integration.workerscale;

import com.xa.mass.integration.workerscale.ScaleApiClient.LabWorker;
import com.xa.mass.integration.workerscale.ScaleOptions.Phase;
import com.xa.mass.integration.workerscale.ScaleDualTaskWorkload.WorkloadResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public-API proof for one offered Java WebSocket Worker scale phase. */
public final class WorkerWebSocketScaleMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerWebSocketScaleMain.class.getName()
    );
    private static final int OBSERVATION_CHUNK_SIZE = 100;
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
            List<String> workerIds = awaitIdentityInventory(options, api);
            String identityDigest = ScaleEvidence.identityDigest(workerIds);
            verifyIdentityBaseline(options, workerIds, identityDigest);

            Convergence convergence = awaitConvergence(
                    options,
                    api,
                    workerIds,
                    "convergence"
            );
            int stableScans = verifyStableHold(
                    options,
                    api,
                    workerIds
            );
            WorkloadResult workload = ScaleDualTaskWorkload.run(
                    options,
                    api,
                    workerIds
            );
            Convergence postWorkConvergence = awaitConvergence(
                    options,
                    api,
                    workerIds,
                    "post-work-convergence"
            );

            summary.put("status", "passed");
            summary.put("preparedIdentities", workerIds.size());
            summary.put("workerIdSetSha256", identityDigest);
            summary.put("consecutiveConvergedScans", convergence.consecutive());
            summary.put("connectedWorkers", convergence.scan().connected());
            summary.put("hotWorkers", convergence.scan().hot());
            summary.put(
                    "connectedAndHotWorkers",
                    convergence.scan().connectedAndHot()
            );
            summary.put(
                    "hotWithoutConnectionWorkers",
                    convergence.scan().hotWithoutConnection()
            );
            summary.put("stableHoldScans", stableScans);
            summary.put(
                    "activeTaskCount",
                    ScaleDualTaskWorkload.TASK_COUNT
            );
            summary.put(
                    "offeredItemsPerTask",
                    options.workloadItemsPerTask()
            );
            summary.put(
                    "totalOfferedItems",
                    Math.multiplyExact(
                            ScaleDualTaskWorkload.TASK_COUNT,
                            options.workloadItemsPerTask()
                    )
            );
            summary.put("appendBatchCount", workload.appendBatchCount());
            summary.put(
                    "taskAFirstSuccessElapsedMillis",
                    workload.taskA().firstSuccessElapsedMillis()
            );
            summary.put(
                    "taskACompletionElapsedMillis",
                    workload.taskA().completionElapsedMillis()
            );
            summary.put("taskASucceededCount", workload.taskA().succeeded());
            summary.put(
                    "taskBFirstSuccessElapsedMillis",
                    workload.taskB().firstSuccessElapsedMillis()
            );
            summary.put(
                    "taskBCompletionElapsedMillis",
                    workload.taskB().completionElapsedMillis()
            );
            summary.put("taskBSucceededCount", workload.taskB().succeeded());
            summary.put(
                    "maximumGlobalNoProgressMillis",
                    workload.maximumNoProgressMillis()
            );
            summary.put(
                    "minimumConnectedDuringWork",
                    workload.minimumConnected()
            );
            summary.put(
                    "postWorkConnectedAndHot",
                    postWorkConvergence.scan().connectedAndHot()
            );
            summary.put("completedAtEpochMillis", System.currentTimeMillis());
            ScaleEvidence.writeSummary(options.summaryFile(), summary);
            LOG.log(
                    System.Logger.Level.INFO,
                    "Worker WebSocket scale phase {0} passed: offered={1}, "
                            + "connectedAndHot={2}, identitySha256={3}",
                    options.phase().wireValue(),
                    options.offeredWorkers(),
                    convergence.scan().connectedAndHot(),
                    identityDigest
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

    private static List<String> awaitIdentityInventory(
            ScaleOptions options,
            ScaleApiClient api
    ) {
        long deadline = deadline(options.maximumConvergenceWait());
        RuntimeException latestFailure = null;
        do {
            try {
                List<LabWorker> workers = api.labWorkers();
                InventoryObservation observation = observeInventory(
                        options,
                        workers
                );
                ScaleEvidence.appendTimeline(
                        options.timelineFile(),
                        observation.timeline(options)
                );
                if (observation.complete()) {
                    return observation.workerIds();
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
            List<LabWorker> workers
    ) {
        int matching = 0;
        int running = 0;
        List<String> workerIds = new ArrayList<>();
        Set<String> labKeys = new HashSet<>();
        for (LabWorker worker : workers) {
            if (!options.workerGroupId().equals(worker.workerGroupId())) {
                throw new IllegalStateException(
                        "Scale Lab contains an unexpected WorkerGroup"
                );
            }
            matching++;
            if (!labKeys.add(worker.labWorkerKey())) {
                throw new IllegalStateException(
                        "Scale Lab contains a duplicate Worker coordinate"
                );
            }
            if ("RUNNING".equals(worker.desiredState())
                    && "RUNNING".equals(worker.runtimeState())) {
                running++;
            }
            if (worker.workerId() != null && !worker.workerId().isBlank()) {
                workerIds.add(worker.workerId());
            }
        }
        Set<String> uniqueIds = new HashSet<>(workerIds);
        if (uniqueIds.size() != workerIds.size()) {
            throw new IllegalStateException(
                    "Scale Lab contains duplicate Server-issued workerIds"
            );
        }
        Collections.sort(workerIds);
        boolean complete = matching == options.offeredWorkers()
                && running == options.offeredWorkers()
                && workerIds.size() == options.offeredWorkers();
        return new InventoryObservation(
                matching,
                running,
                List.copyOf(workerIds),
                complete
        );
    }

    private static void verifyIdentityBaseline(
            ScaleOptions options,
            List<String> workerIds,
            String identityDigest
    ) {
        if (options.phase() == Phase.INITIAL) {
            ScaleEvidence.writeBaseline(
                    options.baselineFile(),
                    options.workerGroupId(),
                    workerIds
            );
        } else {
            List<String> baseline = ScaleEvidence.readBaseline(
                    options.baselineFile(),
                    options.workerGroupId()
            );
            if (!baseline.equals(workerIds)) {
                throw new IllegalStateException(
                        "Server restart changed the Worker identity set"
                );
            }
            if (!ScaleEvidence.identityDigest(baseline).equals(identityDigest)) {
                throw new IllegalStateException(
                        "Worker identity digest changed after Server restart"
                );
            }
        }
        ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                "atEpochMillis", System.currentTimeMillis(),
                "phase", options.phase().wireValue(),
                "event", "identity-set-established",
                "workerCount", workerIds.size(),
                "workerIdSetSha256", identityDigest
        ));
    }

    private static Convergence awaitConvergence(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> workerIds,
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
                        workerIds,
                        stage
                );
                if (latest.connectedAndHot() >= options.minimumConverged()) {
                    consecutive++;
                } else {
                    consecutive = 0;
                }
                if (consecutive >= REQUIRED_CONSECUTIVE_SCANS
                        && latest.hotWithoutConnection() == 0) {
                    return new Convergence(latest, consecutive);
                }
            } catch (RuntimeException error) {
                latestFailure = error;
                consecutive = 0;
                appendFailure(options, "convergence-scan", error);
            }
            sleep(options.scanInterval());
        } while (System.nanoTime() < deadline);
        IllegalStateException timeout = new IllegalStateException(
                "Worker network and scheduling projections did not converge"
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
            List<String> workerIds
    ) {
        if (options.stableHold().isZero()) {
            return 0;
        }
        long started = System.nanoTime();
        long holdNanos = options.stableHold().toNanos();
        int scans = 0;
        while (true) {
            Scan observed = scan(options, api, workerIds, "stable-hold");
            scans++;
            if (observed.connectedAndHot() < options.minimumConverged()) {
                throw new IllegalStateException(
                        "Worker convergence fell below the stable-hold threshold"
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
            List<String> workerIds,
            String stage
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
        Scan scan = new Scan(
                connected,
                hot,
                connectedAndHot,
                hotWithoutConnection
        );
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("atEpochMillis", System.currentTimeMillis());
        evidence.put("phase", options.phase().wireValue());
        evidence.put("event", "projection-scan");
        evidence.put("stage", stage);
        evidence.put("offeredWorkers", workerIds.size());
        evidence.put("connectedWorkers", connected);
        evidence.put("hotWorkers", hot);
        evidence.put("connectedAndHotWorkers", connectedAndHot);
        evidence.put("hotWithoutConnectionWorkers", hotWithoutConnection);
        ScaleEvidence.appendTimeline(options.timelineFile(), evidence);
        return scan;
    }

    private static Map<String, Object> baseSummary(
            ScaleOptions options,
            long startedAt
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proofId", options.proofId());
        result.put("lane", "worker-websocket-scale");
        result.put("phase", options.phase().wireValue());
        result.put("offeredWorkers", options.offeredWorkers());
        result.put("minimumConnectedAndHot", options.minimumConverged());
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
            List<String> workerIds,
            boolean complete
    ) {

        Map<String, Object> timeline(ScaleOptions options) {
            return Map.of(
                    "atEpochMillis", System.currentTimeMillis(),
                    "phase", options.phase().wireValue(),
                    "event", "identity-inventory-observed",
                    "observedWorkers", observedWorkers,
                    "runningWorkers", runningWorkers,
                    "workersWithIdentity", workerIds.size(),
                    "expectedWorkers", options.offeredWorkers()
            );
        }
    }

    private record Scan(
            int connected,
            int hot,
            int connectedAndHot,
            int hotWithoutConnection
    ) {
    }

    private record Convergence(Scan scan, int consecutive) {
    }
}
