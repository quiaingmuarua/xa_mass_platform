package com.xa.mass.scenariorpc;

import java.util.List;

@FunctionalInterface
public interface ScenarioRpcResultSink {

    void accept(List<ScenarioRpcResult> newResults) throws Exception;
}
