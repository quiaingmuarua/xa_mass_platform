package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class WorkerServiceabilityDispatchPolicy {

    private static final int PROBE_ROUND_LIMIT = 100;

    private final WorkerScoreCore workerScores;
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerServiceabilityRuntime serviceability;
    private final LongSupplier currentTimeMillis;

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime serviceability
    ) {
        this(
                workerScores,
                workerCatalog,
                serviceability,
                System::currentTimeMillis
        );
    }

    WorkerServiceabilityDispatchPolicy(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime serviceability,
            LongSupplier currentTimeMillis
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.serviceability = Objects.requireNonNull(
                serviceability,
                "serviceability"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchProbes(
            List<String> orderedWorkerGroupIds,
            WorkerServiceabilityDispatchConfig config
    ) {
        List<String> workerGroupIds = List.copyOf(Objects.requireNonNull(
                orderedWorkerGroupIds,
                "orderedWorkerGroupIds"
        ));
        Objects.requireNonNull(config, "config");
        if (workerGroupIds.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> uniqueGroupIds = new LinkedHashSet<>(
                workerGroupIds
        );
        if (uniqueGroupIds.size() != workerGroupIds.size()
                || uniqueGroupIds.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "orderedWorkerGroupIds must contain unique non-empty IDs"
            );
        }

        long nowMillis = currentTimeMillis.getAsLong();
        long hotProbeCutoffMillis = hotProbeCutoffMillis(
                nowMillis,
                config.hotEligibilityFloorMillis(),
                config.hotProbeStaleAfterMillis()
        );
        Set<String> excludedEndpoints = Set.copyOf(
                config.probeExcludedEndpointManagerIds()
        );
        int remainingProbeBudget = PROBE_ROUND_LIMIT;
        int offered = 0;
        for (String workerGroupId : workerGroupIds) {
            if (remainingProbeBudget == 0) {
                break;
            }
            Map<String, Long> hot = workerScores.observeHotCandidatesBefore(
                    workerGroupId,
                    hotProbeCutoffMillis,
                    remainingProbeBudget
            );
            Map<String, Long> candidates = hot.isEmpty()
                    ? workerScores.observeRecoveryRecheckCandidates(
                            workerGroupId,
                            remainingProbeBudget
                    )
                    : hot;
            if (candidates.isEmpty()) {
                continue;
            }

            List<String> workerIds = List.copyOf(candidates.keySet());
            Map<String, WorkerDescriptor> descriptors =
                    workerCatalog.getWorkerDescriptors(
                            workerIds
                    );
            LinkedHashMap<String, Long> observedScores = new LinkedHashMap<>();
            LinkedHashMap<String, WorkerDescriptor> probeDescriptors =
                    new LinkedHashMap<>();
            for (var candidate : candidates.entrySet()) {
                WorkerDescriptor descriptor = descriptors.get(
                        candidate.getKey()
                );
                if (descriptor == null
                        || !workerGroupId.equals(descriptor.workerGroupId())
                        || !candidate.getKey().equals(
                                descriptor.workerId()
                        )) {
                    continue;
                }
                if (excludedEndpoints.contains(
                        descriptor.endpointManagerId()
                )) {
                    coldPark(
                            workerGroupId,
                            candidate.getKey(), candidate.getValue(),
                            !hot.isEmpty()
                    );
                    continue;
                }
                probeDescriptors.put(candidate.getKey(), descriptor);
                observedScores.put(candidate.getKey(), candidate.getValue());
            }

            Map<String, WorkerScoreTransitionResult> results =
                    workerScores.deferObservedToRecovery(
                            workerGroupId,
                            observedScores,
                            config.recheckDelayMillis()
                    );
            List<String> heldWorkerIds = new ArrayList<>();
            probeDescriptors.keySet().forEach(workerId -> {
                WorkerScoreTransitionResult result = results.get(workerId);
                if (result != null && result.status()
                        == WorkerScoreTransitionStatus.TRANSITIONED) {
                    heldWorkerIds.add(workerId);
                }
            });
            if (heldWorkerIds.size() > remainingProbeBudget) {
                throw new IllegalStateException(
                        "Serviceability hold exceeded the round budget"
                );
            }
            remainingProbeBudget -= heldWorkerIds.size();
            offered += offerProbes(heldWorkerIds, probeDescriptors);
        }
        return offered;
    }

    private void coldPark(
            String workerGroupId,
            String workerId, long observedScore,
            boolean observedHot
    ) {
        long recoveryScore = observedScore;
        if (observedHot) {
            var toggled = workerScores.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    recoveryScore
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED
                    || toggled.score() == null) {
                return;
            }
            recoveryScore = toggled.score();
        }
        workerScores.parkObservedRecoveryScore(
                workerGroupId,
                workerId,
                recoveryScore
        );
    }

    private int offerProbes(
            List<String> workerIds,
            Map<String, WorkerDescriptor> descriptors
    ) {
        Map<String, List<String>> workerIdsByAdapter = new LinkedHashMap<>();
        workerIds.forEach(workerId -> workerIdsByAdapter.computeIfAbsent(
                descriptors.get(workerId).endpointManagerId(),
                ignored -> new ArrayList<>()
        ).add(workerId));
        int offered = 0;
        for (Map.Entry<String, List<String>> adapter
                : workerIdsByAdapter.entrySet()) {
            Map<String, ProbeRequestOfferStatus> statuses =
                    serviceability.offerProbeRequests(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            offered += (int) statuses.values().stream()
                    .filter(status -> status == ProbeRequestOfferStatus.OFFERED)
                    .count();
        }
        return offered;
    }

    private static long hotProbeCutoffMillis(
            long nowMillis,
            long hotEligibilityFloorMillis,
            long hotProbeStaleAfterMillis
    ) {
        long staleBeforeMillis = nowMillis <= hotProbeStaleAfterMillis
                ? 0
                : nowMillis - hotProbeStaleAfterMillis;
        return Math.max(
                hotEligibilityFloorMillis,
                staleBeforeMillis
        );
    }

}
