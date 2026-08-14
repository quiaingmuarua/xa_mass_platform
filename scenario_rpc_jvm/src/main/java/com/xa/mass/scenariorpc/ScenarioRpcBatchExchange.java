package com.xa.mass.scenariorpc;

import java.util.List;
import java.util.Map;

public interface ScenarioRpcBatchExchange {

    void append(
            ScenarioRpcDescriptor scenario,
            List<ScenarioRpcItem> items
    );

    Map<String, Map<String, Object>> loadResults(
            ScenarioRpcDescriptor scenario,
            List<String> pendingMessageIds
    );
}
