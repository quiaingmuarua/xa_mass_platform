package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.storage.api.TaskDetailStore;

/**
 * Engine-local compatibility attempt summary so storage projection stats do
 * not leak through runtime or validator seams.
 */
@CompatibilityProjectionOnly
record CompatibilityAttemptStats(long totalAttempts,
                                 long activeAttempts,
                                 long runningAttempts,
                                 long failedAttempts,
                                 long expiredAttempts) {

    static CompatibilityAttemptStats from(TaskDetailStore.TaskMessageAttemptStats stats) {
        if (stats == null) {
            return new CompatibilityAttemptStats(0, 0, 0, 0, 0);
        }
        return new CompatibilityAttemptStats(
                stats.getTotalAttempts(),
                stats.getActiveAttempts(),
                stats.getRunningAttempts(),
                stats.getFailedAttempts(),
                stats.getExpiredAttempts()
        );
    }
}
