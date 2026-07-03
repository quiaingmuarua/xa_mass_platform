package com.xa.mass.task.runtime.starter;

import com.xa.mass.sdk.TaskReadOperations;

/**
 * Approved external task read-view surface hosted outside task-runtime core.
 *
 * <p>The shape intentionally reuses the current SDK read snapshots so server and
 * embedded callers do not need a second DTO family while the old
 * {@link TaskReadOperations} facade is being retired.
 */
public interface TaskReadViewPort extends TaskReadOperations {
}
