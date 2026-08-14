package com.xa.mass.server.scenariorpc;

enum ScenarioRpcInstanceStatus {
    CREATED("created"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String wireValue;

    ScenarioRpcInstanceStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED;
    }
}
