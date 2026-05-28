package com.xa.mass.storage.api;

import com.xa.mass.base.model.Task;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Current task-shell lifecycle query surface.
 *
 * <p>This is not runtime ready-queue truth. Dispatch admission must start from
 * the runtime queue. This query exists only for lifecycle policies over stable
 * task shells, such as max-runtime deadline termination.
 */
public interface TaskShellLifecycleQuery {

    List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit);
}
