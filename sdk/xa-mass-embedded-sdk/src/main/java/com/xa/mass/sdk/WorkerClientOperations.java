package com.xa.mass.sdk;

import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.sdk.worker.WorkerInvocation;
import com.xa.mass.sdk.worker.WorkerPollResult;
import com.xa.mass.sdk.worker.WorkerResultSubmission;

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

    default WorkerPollResult pollTasksResult(String workerId, int maxMessages) {
        return pollTasksResult(workerId, maxMessages, 0L);
    }

    WorkerPollResult pollTasksResult(String workerId, int maxMessages, long timeoutMillis);

    default List<WorkerInvocation> pollTasks(String workerId, int maxMessages) {
        return pollTasks(workerId, maxMessages, 0L);
    }

    default List<WorkerInvocation> pollTasks(String workerId, int maxMessages, long timeoutMillis) {
        return pollTasksResult(workerId, maxMessages, timeoutMillis).getItems();
    }

    boolean submitResult(String workerId, WorkerResultSubmission request);
}
