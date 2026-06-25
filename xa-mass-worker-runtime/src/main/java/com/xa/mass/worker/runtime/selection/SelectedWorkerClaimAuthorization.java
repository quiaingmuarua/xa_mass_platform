package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.api.WorkerClaimTarget;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Claim-time authorization produced by worker-runtime selection.
 *
 * <p>The event-code set is intentionally not exposed outside this package.
 * Engine can only ask the selected handle to build the current task-runtime
 * claim target.</p>
 */
final class SelectedWorkerClaimAuthorization {

    private final Set<String> supportedEventCodes;

    private SelectedWorkerClaimAuthorization(Collection<String> supportedEventCodes) {
        this.supportedEventCodes = normalize(supportedEventCodes);
    }

    public static SelectedWorkerClaimAuthorization unrestricted() {
        return new SelectedWorkerClaimAuthorization(Set.of());
    }

    public static SelectedWorkerClaimAuthorization eventCodes(Collection<String> supportedEventCodes) {
        return new SelectedWorkerClaimAuthorization(supportedEventCodes);
    }

    public WorkerClaimTarget toClaimTarget(String workerGroupId,
                                           String workerId,
                                           String batchId,
                                           String selectionToken,
                                           Long scoreBandClaimScore,
                                           int capacity) {
        return WorkerClaimTarget.groupScoped(
                workerGroupId,
                workerId,
                batchId,
                selectionToken,
                scoreBandClaimScore,
                capacity,
                supportedEventCodes);
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }
}
