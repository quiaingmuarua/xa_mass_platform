package com.xa.mass.engine;

/**
 * Read-only in-process task listener counts for diagnostics and tests.
 */
public record TaskEventListenerSnapshot(
        int taskCreatedListeners,
        int taskAssignedListeners,
        int taskReadyListeners,
        int taskDispatchListeners,
        int taskTerminalListeners,
        int taskWorkAttemptClosedListeners,
        int taskWorkLogicallyFinalListeners
) {
}
