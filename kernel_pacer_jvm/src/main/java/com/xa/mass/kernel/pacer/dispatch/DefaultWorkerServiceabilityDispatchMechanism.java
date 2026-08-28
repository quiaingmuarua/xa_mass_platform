package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.ServiceabilityPolarity;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerServiceabilityObservation;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerSweepPage;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DefaultWorkerServiceabilityDispatchMechanism
        implements WorkerServiceabilityDispatchMechanism {

    private final WorkerScoreCore workerScores;
    private final WorkerResourceCatalog workerCatalog;

    DefaultWorkerServiceabilityDispatchMechanism(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
    }

    @Override
    public WorkerSweepPage observePreEpochHot(
            String workerGroupId,
            long hotEligibilityFloorMillis,
            WorkerSweepCursor cursor,
            int limit
    ) {
        requireGroup(workerGroupId);
        Objects.requireNonNull(cursor, "cursor");
        List<WorkerScoreObservation> page =
                workerScores.acquirePreEpochHotCandidates(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        cursor.encodedScore(),
                        limit
                );
        return sweepPage(workerGroupId, page);
    }

    @Override
    public WorkerSweepPage observeRecovery(
            String workerGroupId,
            WorkerSweepCursor cursor,
            int limit
    ) {
        requireGroup(workerGroupId);
        Objects.requireNonNull(cursor, "cursor");
        List<WorkerScoreObservation> page =
                workerScores.acquireRecoveryRecheckCandidates(
                        workerGroupId,
                        cursor.encodedScore(),
                        limit
                );
        return sweepPage(workerGroupId, page);
    }

    @Override
    public List<WorkerServiceabilityObservation> recheck(
            String workerGroupId,
            List<WorkerCandidateReference> candidates
    ) {
        requireGroup(workerGroupId);
        List<WorkerCandidateReference> immutable = List.copyOf(
                Objects.requireNonNull(candidates, "candidates")
        );
        if (immutable.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (WorkerCandidateReference candidate : immutable) {
            requireReference(workerGroupId, candidate);
            if (!workerIds.add(candidate.workerId())) {
                throw new IllegalArgumentException(
                        "candidates must be unique by workerId"
                );
            }
        }
        List<String> orderedIds = List.copyOf(workerIds);
        Map<String, WorkerScoreState> states = workerScores.getScoreStates(
                workerGroupId,
                orderedIds
        );
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        orderedIds
                );
        List<WorkerServiceabilityObservation> observations =
                new ArrayList<>();
        for (WorkerCandidateReference candidate : immutable) {
            WorkerScoreState state = states.get(candidate.workerId());
            WorkerDescriptor descriptor = descriptors.get(
                    candidate.workerId()
            );
            if (state == null
                    || state.score() != candidate.encodedScore()
                    || descriptor == null
                    || !workerGroupId.equals(descriptor.workerGroupId())
                    || !candidate.workerId().equals(descriptor.workerId())) {
                continue;
            }
            observations.add(new WorkerServiceabilityObservation(
                    workerGroupId,
                    candidate.workerId(),
                    state.polarity() == WorkerScorePolarity.HOT_ACQUIRE
                            ? ServiceabilityPolarity.HOT
                            : ServiceabilityPolarity.RECOVERY,
                    state.timeMillis(),
                    state.laneRank(),
                    descriptor.endpointManagerId(),
                    candidate
            ));
        }
        return List.copyOf(observations);
    }

    @Override
    public int coldParkExcluded(
            List<WorkerServiceabilityObservation> workers,
            int maxRecoveryAttempts
    ) {
        int parked = 0;
        for (WorkerServiceabilityObservation worker : List.copyOf(
                Objects.requireNonNull(workers, "workers")
        )) {
            WorkerCandidateReference reference = worker.reference();
            requireReference(worker.workerGroupId(), reference);
            if (worker.timeMillis() == WorkerScoreCore.PAUSE_TIME_MILLIS) {
                continue;
            }
            long recoveryScore = reference.encodedScore();
            if (worker.polarity() == ServiceabilityPolarity.HOT) {
                var toggled = workerScores.toggleCurrentPolarity(
                        worker.workerGroupId(),
                        worker.workerId(),
                        recoveryScore
                );
                if (toggled.status()
                        != WorkerScoreTransitionStatus.TRANSITIONED
                        || toggled.score() == null) {
                    continue;
                }
                recoveryScore = toggled.score();
            }
            var exhausted = workerScores.exhaustRecoveryRecheck(
                    worker.workerGroupId(),
                    worker.workerId(),
                    recoveryScore,
                    maxRecoveryAttempts
            );
            if (exhausted.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED) {
                parked++;
            }
        }
        return parked;
    }

    private static WorkerSweepPage sweepPage(
            String workerGroupId,
            List<WorkerScoreObservation> page
    ) {
        List<WorkerScoreObservation> immutable = List.copyOf(
                Objects.requireNonNull(page, "page")
        );
        List<WorkerCandidateReference> candidates = immutable.stream()
                .map(row -> new WorkerCandidateReference(
                        workerGroupId,
                        row.workerId(),
                        row.score()
                ))
                .toList();
        WorkerSweepCursor nextCursor = immutable.isEmpty()
                ? WorkerSweepCursor.start()
                : WorkerSweepCursor.fromEncodedScore(
                        immutable.get(immutable.size() - 1).score()
                );
        return new WorkerSweepPage(candidates, nextCursor);
    }

    private static void requireReference(
            String workerGroupId,
            WorkerCandidateReference reference
    ) {
        Objects.requireNonNull(reference, "reference");
        if (!workerGroupId.equals(reference.workerGroupId())) {
            throw new IllegalArgumentException(
                    "Worker reference group mismatch"
            );
        }
    }

    private static void requireGroup(String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerGroupId must be non-blank"
            );
        }
    }
}
