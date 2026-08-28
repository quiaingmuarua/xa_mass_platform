package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed Worker serviceability mechanism used by production Kernel Pacers. */
public final class DefaultWorkerServiceabilityEvents
        implements WorkerServiceabilityEvents {

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerScoreCore workerScores;

    public DefaultWorkerServiceabilityEvents(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScores
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
    }

    @Override
    public void onConnected(
            Map<String, Long> observedAtByWorkerId,
            long hotEligibilityFloorMillis
    ) {
        requireFloor(hotEligibilityFloorMillis);
        apply(
                validatedEvidence(observedAtByWorkerId),
                EventKind.CONNECTED,
                hotEligibilityFloorMillis,
                0
        );
    }

    @Override
    public void onRouteUnavailable(
            Map<String, Long> observedAtByWorkerId
    ) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                EventKind.ROUTE_UNAVAILABLE,
                0,
                0
        );
    }

    @Override
    public void onProbeUnavailable(
            Map<String, Long> observedAtByWorkerId,
            int maxRecoveryAttempts
    ) {
        if (maxRecoveryAttempts < 1
                || maxRecoveryAttempts > WorkerScoreCore.MAX_LANE_RANK) {
            throw new IllegalArgumentException(
                    "maxRecoveryAttempts must be between 1 and 99"
            );
        }
        apply(
                validatedEvidence(observedAtByWorkerId),
                EventKind.PROBE_UNAVAILABLE,
                0,
                maxRecoveryAttempts
        );
    }

    private void apply(
            LinkedHashMap<String, Long> evidence,
            EventKind eventKind,
            long hotEligibilityFloorMillis,
            int maxRecoveryAttempts
    ) {
        if (evidence.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> groupIds = groupIds(
                new ArrayList<>(evidence.keySet())
        );
        LinkedHashMap<String, LinkedHashMap<String, Long>> evidenceByGroup =
                new LinkedHashMap<>();
        evidence.forEach((workerId, observedAtMillis) -> {
            String groupId = groupIds.get(workerId);
            if (groupId != null) {
                evidenceByGroup.computeIfAbsent(
                        groupId,
                        ignored -> new LinkedHashMap<>()
                ).put(workerId, observedAtMillis);
            }
        });

        evidenceByGroup.forEach((workerGroupId, groupEvidence) -> {
            Map<String, WorkerScoreState> states = workerScores.getScoreStates(
                    workerGroupId,
                    new ArrayList<>(groupEvidence.keySet())
            );
            groupEvidence.forEach((workerId, observedAtMillis) -> {
                WorkerScoreState state = states.get(workerId);
                if (state == null
                        || state.timeMillis()
                        == WorkerScoreCore.PAUSE_TIME_MILLIS) {
                    return;
                }
                switch (eventKind) {
                    case CONNECTED -> applyConnected(
                            workerGroupId,
                            workerId,
                            state,
                            hotEligibilityFloorMillis
                    );
                    case ROUTE_UNAVAILABLE -> applyRouteUnavailable(
                            workerGroupId,
                            workerId,
                            state
                    );
                    case PROBE_UNAVAILABLE -> applyProbeUnavailable(
                            workerGroupId,
                            workerId,
                            state,
                            observedAtMillis,
                            maxRecoveryAttempts
                    );
                }
            });
        });
    }

    private LinkedHashMap<String, String> groupIds(List<String> workerIds) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int limit = WorkerResourceCatalog.MAX_WORKER_GROUP_LOOKUP_LIMIT;
        for (int offset = 0; offset < workerIds.size(); offset += limit) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + limit, workerIds.size())
            );
            workerCatalog.getWorkerGroupIds(chunk).forEach((workerId, groupId) -> {
                if (groupId != null) {
                    result.put(workerId, groupId);
                }
            });
        }
        return result;
    }

    private void applyConnected(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            long hotEligibilityFloorMillis
    ) {
        if (state.polarity() == WorkerScorePolarity.RECOVERY_RECHECK) {
            var toggled = workerScores.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    state.score()
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED) {
                return;
            }
        }
        workerScores.rewriteCurrentScores(
                workerGroupId,
                List.of(workerId),
                hotEligibilityFloorMillis,
                WorkerScoreCore.MIN_LANE_RANK
        );
    }

    private void applyRouteUnavailable(
            String workerGroupId,
            String workerId,
            WorkerScoreState state
    ) {
        if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            workerScores.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    state.score()
            );
        }
    }

    private void applyProbeUnavailable(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            long observedAtMillis,
            int maxRecoveryAttempts
    ) {
        if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            var toggled = workerScores.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    state.score()
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED) {
                return;
            }
            workerScores.rewriteCurrentScores(
                    workerGroupId,
                    List.of(workerId),
                    observedAtMillis,
                    WorkerScoreCore.MIN_LANE_RANK
            );
            return;
        }
        int nextAttempt = state.laneRank() + 1;
        if (nextAttempt >= maxRecoveryAttempts) {
            workerScores.exhaustRecoveryRecheck(
                    workerGroupId,
                    workerId,
                    state.score(),
                    maxRecoveryAttempts
            );
            return;
        }
        workerScores.rewriteCurrentScores(
                workerGroupId,
                List.of(workerId),
                observedAtMillis,
                nextAttempt
        );
    }

    private static LinkedHashMap<String, Long> validatedEvidence(
            Map<String, Long> source
    ) {
        Objects.requireNonNull(source, "observedAtByWorkerId");
        LinkedHashMap<String, Long> copied = new LinkedHashMap<>();
        source.forEach((workerId, observedAtMillis) -> {
            requireNonBlank(workerId, "workerId");
            if (observedAtMillis == null || observedAtMillis <= 0) {
                throw new IllegalArgumentException(
                        "observedAtMillis must be positive"
                );
            }
            copied.put(workerId, observedAtMillis);
        });
        return copied;
    }

    private static void requireFloor(long floor) {
        if (floor < WorkerScoreCore.SLOT_MILLIS
                || floor % WorkerScoreCore.SLOT_MILLIS != 0
                || floor > WorkerScoreCore.MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    "hotEligibilityFloorMillis must be a valid "
                            + "score-slot-aligned time"
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private enum EventKind {
        CONNECTED,
        ROUTE_UNAVAILABLE,
        PROBE_UNAVAILABLE
    }
}
