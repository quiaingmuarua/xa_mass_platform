package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;

/**
 * Runtime facade for third-party polling workers that live outside the JVM.
 *
 * <p>This surface keeps external worker integration on the task-backed polling
 * path instead of teaching external clients about gateway-specific transport
 * adapters.
 */
public interface ExternalWorkerOperations {

    void registerWorker(WorkerRegistration request);

    void registerWorkerContext(WorkerContextRegistration request);

    void workerOnline(String workerId, String reason);

    void workerHeartbeat(String workerId, String reason);

    void workerOffline(String workerId, String reason);

    List<TaskDispatchItem> pollTasks(String workerId, int maxMessages);

    boolean submitResult(String workerId, TaskResultReport report);
}
