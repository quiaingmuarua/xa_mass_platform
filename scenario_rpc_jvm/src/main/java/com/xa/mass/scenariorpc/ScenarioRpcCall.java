package com.xa.mass.scenariorpc;

import java.util.Map;

@FunctionalInterface
public interface ScenarioRpcCall {

    Map<String, Object> call(
            String workerGroupId,
            String messageId,
            String eventCode,
            Map<String, Object> payload
    );
}
