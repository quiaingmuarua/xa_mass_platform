package com.xa.mass.server.worker.binding;

import java.util.List;
import java.util.Map;

interface WorkerBindingRegistry {

    String bindIfAbsent(String workerId, String endpointManagerId);

    String getEndpointManagerId(String workerId);

    Map<String, String> getEndpointManagerIds(List<String> workerIds);
}
