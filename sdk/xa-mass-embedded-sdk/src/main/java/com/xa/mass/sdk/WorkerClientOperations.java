package com.xa.mass.sdk;

import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.sdk.worker.WorkerAction;
import com.xa.mass.sdk.worker.WorkerActionReply;
import com.xa.mass.sdk.worker.WorkerPollResult;

import java.util.List;

/**
 * Embedded worker runtime interaction surface.
 */
public interface WorkerClientOperations {

    String getWorkerTransportHint(String workerId);

    EmbeddedPullWorkerSession pullWorker(String workerId);

    void workerOnline(String workerId, String sessionToken, String reason);

    void workerHeartbeat(String workerId, String sessionToken, String reason);

    void workerOffline(String workerId, String sessionToken, String reason);

    default WorkerPollResult pollActionsResult(String workerId, int maxMessages) {
        return pollActionsResult(workerId, maxMessages, 0L);
    }

    WorkerPollResult pollActionsResult(String workerId, int maxMessages, long timeoutMillis);

    default List<WorkerAction> pollActions(String workerId, int maxMessages) {
        return pollActions(workerId, maxMessages, 0L);
    }

    default List<WorkerAction> pollActions(String workerId, int maxMessages, long timeoutMillis) {
        return pollActionsResult(workerId, maxMessages, timeoutMillis).getItems();
    }

    boolean submitActionReply(String workerId, WorkerActionReply request);
}
