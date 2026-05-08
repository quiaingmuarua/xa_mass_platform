package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;

/**
 * Runtime facade for third-party workers that live outside the JVM.
 *
 * <p>Worker registration stays transport-neutral. Polling-specific session
 * operations remain explicit so realtime workers are not silently routed
 * through pull-session machinery.
 */
public interface ExternalWorkerOperations {

    void registerWorker(WorkerRegistration request);

    void registerWorkerContext(WorkerContextRegistration request);

    String getWorkerAdapterId(String workerId);

    String getWorkerTransportHint(String workerId);

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
