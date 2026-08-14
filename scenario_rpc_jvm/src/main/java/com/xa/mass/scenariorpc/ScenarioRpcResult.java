package com.xa.mass.scenariorpc;

import java.util.Map;

public record ScenarioRpcResult(
        String workerGroupId,
        String messageId,
        String eventCode,
        Map<String, Object> input,
        Map<String, Object> result
) {
}
