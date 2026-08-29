package com.xa.mass.kernel.pacer.dispatch;

import java.util.List;
import java.util.Objects;

interface WorkerServiceabilityDispatchMechanism {

    long SLOT_MILLIS = 100;
    long MAX_TIME_MILLIS = 9_999_999_999_900L;
    int MAX_RECOVERY_ATTEMPTS = 99;

    WorkerSweepPage observePreEpochHot(
            String workerGroupId,
            long hotEligibilityFloorMillis,
            WorkerSweepCursor cursor,
            int limit
    );

    WorkerSweepPage observeRecovery(
            String workerGroupId,
            WorkerSweepCursor cursor,
            int limit
    );

    List<WorkerServiceabilityObservation> recheck(
            String workerGroupId,
            List<WorkerCandidateReference> candidates
    );

    List<WorkerServiceabilityObservation> holdForProbe(
            List<WorkerServiceabilityObservation> workers
    );

    int coldPark(
            List<WorkerServiceabilityObservation> workers,
            int maxRecoveryAttempts
    );

    enum ServiceabilityPolarity {
        HOT,
        RECOVERY
    }

    record WorkerSweepPage(
            List<WorkerCandidateReference> candidates,
            WorkerSweepCursor nextCursor
    ) {
        public WorkerSweepPage {
            candidates = List.copyOf(Objects.requireNonNull(
                    candidates,
                    "candidates"
            ));
            Objects.requireNonNull(nextCursor, "nextCursor");
        }

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    record WorkerServiceabilityObservation(
            String workerGroupId,
            String workerId,
            ServiceabilityPolarity polarity,
            long timeMillis,
            int laneRank,
            String endpointManagerId,
            WorkerCandidateReference reference
    ) {
        public WorkerServiceabilityObservation {
            if (workerGroupId == null || workerGroupId.isBlank()
                    || workerId == null || workerId.isBlank()
                    || endpointManagerId == null
                    || endpointManagerId.isBlank()) {
                throw new IllegalArgumentException(
                        "Worker observation identities must be non-blank"
                );
            }
            Objects.requireNonNull(polarity, "polarity");
            Objects.requireNonNull(reference, "reference");
        }
    }
}
