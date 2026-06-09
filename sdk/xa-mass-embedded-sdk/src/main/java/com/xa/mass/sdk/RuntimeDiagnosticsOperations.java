package com.xa.mass.sdk;

import java.util.List;
import java.util.Map;

/**
 * Operator-oriented runtime diagnostics surface.
 *
 * <p>This is an explicit SDK-owned read model for runtime/session/queue
 * inspection. It is intentionally outside the task/worker mainline API.
 */
public interface RuntimeDiagnosticsOperations {

    List<Map<String, Object>> listSessions();

    Map<String, Object> getSessionStats();

    Map<String, Object> getQueueDetail();

    Map<String, Object> getQueueMetrics();

    boolean isWorkerLocked(String workerId);

    List<String> listLockedWorkerIds();
}
