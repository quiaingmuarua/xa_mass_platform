package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

interface WorkerCandidateMechanism {

    List<WorkerCandidateObservation> observeHot(
            String workerGroupId,
            @Nullable Long hotEligibilityFloorMillis,
            int limit
    );

    List<WorkerCandidateObservation> observeExplicit(
            String workerGroupId,
            List<String> workerIds,
            @Nullable Long hotEligibilityFloorMillis
    );

    List<WorkerCandidateObservation> consumePrecomputed(
            String candidateId,
            String workerGroupId,
            int limit
    );

    List<WorkerCandidateObservation> leaseSelected(
            String workerGroupId,
            List<WorkerCandidateObservation> selected,
            long leaseUntilMillis,
            LeaseMode mode
    );

    void appendCandidates(
            String candidateId,
            List<WorkerCandidateObservation> workers,
            long expiresAtMillis
    );

    enum LeaseMode {
        ACQUIRE,
        RENEW
    }

    record WorkerCandidateObservation(
            String workerId,
            String workerGroupId,
            WorkerDescriptor descriptor,
            WorkerCandidateReference reference
    ) {
        public WorkerCandidateObservation {
            if (workerId == null || workerId.isBlank()
                    || workerGroupId == null || workerGroupId.isBlank()) {
                throw new IllegalArgumentException(
                        "Worker candidate identities must be non-blank"
                );
            }
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(reference, "reference");
            if (!workerId.equals(descriptor.workerId())
                    || !workerGroupId.equals(descriptor.workerGroupId())) {
                throw new IllegalArgumentException(
                        "Worker candidate descriptor identity mismatch"
                );
            }
        }
    }
}
