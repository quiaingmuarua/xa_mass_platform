package com.xa.mass.starter;

import java.util.Map;

/**
 * Internal typed view for one runtime executor diagnostic snapshot.
 */
record RuntimeExecutorDiagnosticsView(boolean available,
                                      long submittedTasks,
                                      long completedTasks,
                                      long rejectedTasks,
                                      int activeTasks,
                                      int pendingTasks,
                                      int maxPendingTasks) {

    Map<String, Object> toMap() {
        return Map.of(
                "available", available,
                "submittedTasks", submittedTasks,
                "completedTasks", completedTasks,
                "rejectedTasks", rejectedTasks,
                "activeTasks", activeTasks,
                "pendingTasks", pendingTasks,
                "maxPendingTasks", maxPendingTasks
        );
    }
}
