package com.xa.mass.sdk;

import java.util.Map;

/**
 * Operator-oriented runtime diagnostics surface.
 *
 * <p>This is an explicit SDK-owned read model for runtime/session/queue
 * inspection. It is intentionally outside the task/worker mainline API, and
 * session rows must not be treated as transport endpoint truth.
 */
public interface RuntimeDiagnosticsOperations {

    Map<String, Object> getQueueDetail();

    Map<String, Object> getQueueMetrics();

    boolean isWorkerLocked(String workerId);
}
