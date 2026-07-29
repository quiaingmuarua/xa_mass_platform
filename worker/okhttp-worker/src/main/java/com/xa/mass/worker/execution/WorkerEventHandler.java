package com.xa.mass.worker.execution;

import java.util.Map;

@FunctionalInterface
public interface WorkerEventHandler {

    Map<String, Object> execute(
            Map<String, Object> payload
    ) throws Exception;
}
