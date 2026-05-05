package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
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

        int releasedContexts = 0;
        for (WorkerContext context : workerStorage.getAllWorkerContexts()) {
            if (context == null || context.getStatus() == null) {
                continue;
            }
            if (context.getStatus() == WorkerContextStatus.RESERVED
                    || context.getStatus() == WorkerContextStatus.OCCUPIED) {
                context.setStatus(WorkerContextStatus.IDLE);
                workerStorage.updateWorkerContextById(context.getWorkerContextId(), context);
                releasedContexts++;
            }
        }

        List<String> lockedWorkers = workerStorage.getLockedWorkers();
        for (String workerId : lockedWorkers) {
            workerStorage.unlockWorker(workerId);
        }

        log.info(
                "Recovered JDBC runtime residue: forcedOfflineWorkers={}, resetWorkerContexts={}, clearedWorkerLocks={}",
                offlineWorkers,
                releasedContexts,
                lockedWorkers.size()
        );
    }
}

