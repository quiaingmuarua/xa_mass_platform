package com.xa.mass.sdk;

import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

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
}
