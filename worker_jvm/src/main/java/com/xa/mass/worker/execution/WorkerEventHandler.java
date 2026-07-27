package com.xa.mass.worker.execution;

import tools.jackson.databind.JsonNode;

@FunctionalInterface
public interface WorkerEventHandler {

    JsonNode execute(JsonNode payload) throws Exception;
}
