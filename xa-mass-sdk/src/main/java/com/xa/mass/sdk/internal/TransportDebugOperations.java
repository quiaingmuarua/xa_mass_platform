package com.xa.mass.sdk.internal;

import java.util.List;
import java.util.Map;

/**
 * Internal/operator-only transport diagnostics and raw side-channel access.
 *
 * <p>This surface is intentionally outside the stable SDK mainline.
 */
public interface TransportDebugOperations {

    List<Map<String, Object>> listSessions();

    Map<String, Object> getSessionStats();

    Map<String, Object> enqueueRawMessage(Map<String, Object> request);

    Map<String, Object> getQueueDetail();

    Map<String, Object> getQueueMetrics();
}
