package com.xa.mass.scenarioworkers;

record ScenarioWorkerCoordinate(
        String workerGroupId,
        String clientWorkerKey
) {

    ScenarioWorkerCoordinate {
        ScenarioWorkerGroupConfig.requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        ScenarioWorkerGroupConfig.requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
    }
}
