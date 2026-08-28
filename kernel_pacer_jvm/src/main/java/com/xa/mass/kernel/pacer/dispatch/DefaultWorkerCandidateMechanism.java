package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.LeaseMode;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class DefaultWorkerCandidateMechanism
        implements WorkerCandidateMechanism {

    private final CandidateWorkerCache candidateCache;
    private final WorkerScoreCore workerScores;
    private final WorkerResourceCatalog workerCatalog;

    DefaultWorkerCandidateMechanism(
            CandidateWorkerCache candidateCache,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog
    ) {
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
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
    public List<WorkerCandidateObservation> observeHot(
            String workerGroupId,
            @Nullable Long hotEligibilityFloorMillis,
            int limit
    ) {
        Map<String, Long> observed = workerScores.acquireHotAcquireCandidates(
                workerGroupId,
                hotEligibilityFloorMillis,
                limit
        );
        return observations(workerGroupId, observed);
    }

    @Override
    public List<WorkerCandidateObservation> observeExplicit(
            String workerGroupId,
            List<String> workerIds,
            @Nullable Long hotEligibilityFloorMillis
    ) {
        Map<String, Long> observed = workerScores.observeDueHotScores(
                workerGroupId,
                workerIds,
                hotEligibilityFloorMillis
        );
        return observations(workerGroupId, observed);
    }

    @Override
    public List<WorkerCandidateObservation> consumePrecomputed(
            String candidateId,
            String workerGroupId,
            int limit
    ) {
        List<CandidateWorkerEntry> cached =
                candidateCache.consumeCandidateWorkers(candidateId, limit);
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (CandidateWorkerEntry entry : cached) {
            if (workerGroupId.equals(entry.workerGroupId())) {
                observed.putIfAbsent(
                        entry.workerId(),
                        entry.workerLeaseScore()
                );
            }
        }
        return observations(workerGroupId, observed);
    }

    @Override
    public List<WorkerCandidateObservation> leaseSelected(
            String workerGroupId,
            List<WorkerCandidateObservation> selected,
            long leaseUntilMillis,
            LeaseMode mode
    ) {
        Objects.requireNonNull(mode, "mode");
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (WorkerCandidateObservation worker : List.copyOf(
                Objects.requireNonNull(selected, "selected")
        )) {
            requireGroup(workerGroupId, worker);
            if (observed.putIfAbsent(
                    worker.workerId(),
                    worker.reference().encodedScore()
            ) != null) {
                throw new IllegalArgumentException(
                        "selected Workers must be unique by workerId"
                );
            }
        }
        if (observed.isEmpty()) {
            return List.of();
        }
        Map<String, WorkerScoreTransitionResult> transitions =
                mode == LeaseMode.ACQUIRE
                        ? workerScores.acquireObservedHotScoreLeases(
                                workerGroupId,
                                observed,
                                leaseUntilMillis
                        )
                        : workerScores.renewActiveHotScoreLeases(
                                workerGroupId,
                                observed,
                                leaseUntilMillis
                        );
        LinkedHashMap<String, Long> leased = new LinkedHashMap<>();
        selected.forEach(worker -> {
            WorkerScoreTransitionResult result = transitions.get(
                    worker.workerId()
            );
            if (result != null
                    && result.score() != null
                    && (result.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED
                    || mode == LeaseMode.RENEW
                    && result.status() == WorkerScoreTransitionStatus.NOOP)) {
                leased.putIfAbsent(worker.workerId(), result.score());
            }
        });
        return observations(workerGroupId, leased);
    }

    @Override
    public void appendCandidates(
            String candidateId,
            List<WorkerCandidateObservation> workers,
            long expiresAtMillis
    ) {
        List<CandidateWorkerEntry> entries = List.copyOf(
                Objects.requireNonNull(workers, "workers")
        ).stream()
                .map(worker -> new CandidateWorkerEntry(
                        worker.workerId(),
                        worker.workerGroupId(),
                        worker.descriptor().endpointManagerId(),
                        worker.reference().encodedScore()
                ))
                .toList();
        candidateCache.appendCandidateWorkers(
                candidateId,
                entries,
                expiresAtMillis
        );
    }

    private List<WorkerCandidateObservation> observations(
            String workerGroupId,
            Map<String, Long> observed
    ) {
        if (observed.isEmpty()) {
            return List.of();
        }
        List<String> workerIds = List.copyOf(observed.keySet());
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        workerIds
                );
        List<WorkerCandidateObservation> result = new ArrayList<>();
        for (String workerId : workerIds) {
            Long score = observed.get(workerId);
            WorkerDescriptor descriptor = descriptors.get(workerId);
            if (score == null
                    || descriptor == null
                    || !workerGroupId.equals(descriptor.workerGroupId())
                    || !workerId.equals(descriptor.workerId())) {
                continue;
            }
            result.add(new WorkerCandidateObservation(
                    workerId,
                    workerGroupId,
                    descriptor,
                    new WorkerCandidateReference(
                            workerGroupId,
                            workerId,
                            score
                    )
            ));
        }
        return List.copyOf(result);
    }

    private static void requireGroup(
            String workerGroupId,
            WorkerCandidateObservation worker
    ) {
        if (!workerGroupId.equals(worker.workerGroupId())
                || !workerGroupId.equals(
                worker.reference().workerGroupId()
        )
                || !worker.workerId().equals(
                worker.reference().workerId()
        )) {
            throw new IllegalArgumentException(
                    "Worker candidate identity mismatch"
            );
        }
    }
}
