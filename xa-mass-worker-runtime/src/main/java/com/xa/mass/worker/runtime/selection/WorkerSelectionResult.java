package com.xa.mass.worker.runtime.selection;

import java.util.List;
import java.util.Map;

/**
 * Bounded selected-worker outcome.
 */
public record WorkerSelectionResult(
        List<SelectedWorkerHandle> selectedWorkers,
        int requestedCount,
        int rejectedCount,
        Map<String, Integer> rejectedCountByReason
) {

    public WorkerSelectionResult {
        selectedWorkers = selectedWorkers == null ? List.of() : List.copyOf(selectedWorkers);
        requestedCount = Math.max(0, requestedCount);
        rejectedCount = Math.max(0, rejectedCount);
        rejectedCountByReason = rejectedCountByReason == null ? Map.of() : Map.copyOf(rejectedCountByReason);
    }

    public static WorkerSelectionResult empty(int requestedCount) {
        return new WorkerSelectionResult(List.of(), requestedCount, 0, Map.of());
    }

    public int selectedCount() {
        return selectedWorkers.size();
    }
}
