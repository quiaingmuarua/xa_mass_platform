package com.xa.mass.sdk;

import java.util.List;
import java.util.Map;

public interface TransportOperations {

    List<Map<String, Object>> listSessions();

    Map<String, Object> getSessionStats();

    Map<String, Object> enqueueRawMessage(Map<String, Object> request);

    Map<String, Object> getQueueDetail();

    Map<String, Object> getQueueMetrics();
}
