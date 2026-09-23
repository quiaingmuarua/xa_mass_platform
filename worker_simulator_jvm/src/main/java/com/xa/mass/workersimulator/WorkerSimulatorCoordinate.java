package com.xa.mass.workersimulator;

record WorkerSimulatorCoordinate(
        String workerGroupId,
        String labWorkerKey
) {

    WorkerSimulatorCoordinate {
        WorkerSimulatorGroupConfig.requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        WorkerSimulatorGroupConfig.requireNonBlank(
                labWorkerKey,
                "labWorkerKey"
        );
    }
}
