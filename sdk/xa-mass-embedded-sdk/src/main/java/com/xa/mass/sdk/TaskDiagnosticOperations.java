package com.xa.mass.sdk;

import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;

import java.util.List;

/**
 * Diagnostic-only task state inspection surface.
 *
 * <p>This interface exists for repo-local operator and validation shells that
 * need bounded state validation/resolution. It is not part of the recommended
 * SDK task shell / ingest / command mainline.</p>
 */
public interface TaskDiagnosticOperations {

    TaskStateValidationResult validateTaskState(String taskId);

    TaskStateResolutionResult resolveTaskState(String taskId);

    TaskWorkStatsSnapshot getTaskWorkStats(String taskId);

    List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId);
}
