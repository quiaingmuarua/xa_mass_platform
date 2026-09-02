package com.xa.mass.server.worker.binding;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

interface WorkerBindingRegistry {

    String bindIfAbsent(String workerId, String endpointManagerId);

    String getEndpointManagerId(String workerId);

    CompletionStage<Map<String, String>> getEndpointManagerIdsAsync(
            List<String> workerIds
    );
}
