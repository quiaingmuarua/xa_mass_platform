package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;

/**
 * Shared runtime helpers for adapter-neutral dispatch outcome generation.
 */
public final class RuntimeDispatchOutcomes {

    private RuntimeDispatchOutcomes() {
    }

    public static List<DispatchOutcome> adapterUnavailable(String adapterId,
                                                           List<TaskDispatchItem> items,
                                                           String reason) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(item -> missingWorker(item)
                        ? DispatchOutcome.invalid(adapterId, item, "workerId must not be blank")
                        : DispatchOutcome.adapterUnavailable(adapterId, item, reason))
                .toList();
    }

    public static boolean missingWorker(TaskDispatchItem item) {
        return item == null || item.getWorkerId() == null || item.getWorkerId().isBlank();
    }
}
