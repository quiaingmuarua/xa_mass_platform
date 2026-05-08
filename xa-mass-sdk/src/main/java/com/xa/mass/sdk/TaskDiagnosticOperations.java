package com.xa.mass.sdk;

/**
 * Diagnostic-only task state inspection surface.
 *
 * <p>This interface exists for repo-local operator and validation shells that
 * need bounded state validation/resolution. It is not part of the recommended
 * SDK task shell / ingest / command mainline.</p>
 */
public interface TaskDiagnosticOperations {

    Object validateTaskState(String taskId);

    Object resolveTaskState(String taskId);
}
