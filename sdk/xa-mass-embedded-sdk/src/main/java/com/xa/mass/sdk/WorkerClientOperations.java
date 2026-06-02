package com.xa.mass.sdk;

import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;

/**
 * External worker runtime interaction surface.
 */
public interface WorkerClientOperations {

    String getWorkerAdapterId(String workerId);

    String getWorkerTransportHint(String workerId);

    PullWorkerSession pullWorker(String workerId);

    void workerOnline(String workerId, String reason);

    void workerHeartbeat(String workerId, String reason);

    void workerOffline(String workerId, String reason);

    default TaskPullResult pollTasksResult(String workerId, int maxMessages) {
        return pollTasksResult(workerId, maxMessages, 0L);
    }

    TaskPullResult pollTasksResult(String workerId, int maxMessages, long timeoutMillis);

    default List<TaskDispatchItem> pollTasks(String workerId, int maxMessages) {
        return pollTasks(workerId, maxMessages, 0L);
    }

    default List<TaskDispatchItem> pollTasks(String workerId, int maxMessages, long timeoutMillis) {
        return pollTasksResult(workerId, maxMessages, timeoutMillis).getDispatchViews();
    }

    boolean submitResult(String workerId, TaskResultReport report);
}
