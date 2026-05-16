package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.storage.api.WorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class JdbcRuntimeResidueRecovery {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuntimeResidueRecovery.class);

    void recover(WorkerStorage workerStorage) {
        int offlineWorkers = 0;
        for (Worker worker : workerStorage.getAllWorkers()) {
            if (worker != null && worker.getStatus() == WorkerStatus.ONLINE) {
                worker.transitionTo(WorkerStatus.OFFLINE);
                workerStorage.updateWorker(worker);
                offlineWorkers++;
            }
        }

        List<String> lockedWorkers = workerStorage.getLockedWorkers();
        for (String workerId : lockedWorkers) {
            workerStorage.unlockWorker(workerId);
        }

        log.info(
                "Recovered JDBC runtime residue: forcedOfflineWorkers={}, clearedWorkerLocks={}",
                offlineWorkers,
                lockedWorkers.size()
        );
    }
}

