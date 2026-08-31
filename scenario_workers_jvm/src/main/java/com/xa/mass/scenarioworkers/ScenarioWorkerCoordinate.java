package com.xa.mass.scenarioworkers;

record ScenarioWorkerCoordinate(
        String workerGroupId,
        String labWorkerKey
) {

    ScenarioWorkerCoordinate {
        ScenarioWorkerGroupConfig.requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        ScenarioWorkerGroupConfig.requireNonBlank(
                labWorkerKey,
                "labWorkerKey"
        );
    }
}
