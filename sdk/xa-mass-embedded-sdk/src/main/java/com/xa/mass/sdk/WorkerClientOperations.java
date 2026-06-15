package com.xa.mass.sdk;

import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.sdk.worker.PulledTaskDispatch;
import com.xa.mass.sdk.worker.TaskPullResult;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;

/**
 * External worker runtime interaction surface.
 */
public interface WorkerClientOperations {

    String getWorkerAdapterId(String workerId);

    String getWorkerTransportHint(String workerId);

    PullWorkerSession pullWorker(String workerId);

    void workerOnline(String workerId, String sessionToken, String reason);

    void workerHeartbeat(String workerId, String sessionToken, String reason);

    void workerOffline(String workerId, String sessionToken, String reason);

    default TaskPullResult pollTasksResult(String workerId, int maxMessages) {
        return pollTasksResult(workerId, maxMessages, 0L);
    }

    TaskPullResult pollTasksResult(String workerId, int maxMessages, long timeoutMillis);

    default List<PulledTaskDispatch> pollTasks(String workerId, int maxMessages) {
        return pollTasks(workerId, maxMessages, 0L);
    }

    default List<PulledTaskDispatch> pollTasks(String workerId, int maxMessages, long timeoutMillis) {
        return pollTasksResult(workerId, maxMessages, timeoutMillis).getItems();
    }

    boolean submitResult(String workerId, TaskResultReport report);
}
