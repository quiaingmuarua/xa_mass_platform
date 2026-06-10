package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceStore;

import java.util.Objects;

/**
 * Runtime-owned pull transport binding resolved for one worker.
 */
public final class ResolvedPullWorkerTransport {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String transportHint;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final WorkerPresenceStore workerPresenceStore;

    public ResolvedPullWorkerTransport(String workerId,
                                       String workerGroupId,
                                       String adapterId,
                                       String transportHint,
                                       TaskPullChannel taskPullChannel,
                                       TaskResultIngestChannel taskResultIngestChannel,
                                       WorkerSystemEventChannel systemEventChannel,
                                       WorkerPresenceStore workerPresenceStore) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.workerGroupId = Objects.requireNonNull(workerGroupId, "workerGroupId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.transportHint = Objects.requireNonNull(transportHint, "transportHint");
        this.taskPullChannel = Objects.requireNonNull(taskPullChannel, "taskPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.workerPresenceStore = Objects.requireNonNull(workerPresenceStore, "workerPresenceStore");
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public TaskPullChannel getTaskPullChannel() {
        return taskPullChannel;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public WorkerPresenceStore getWorkerPresenceStore() {
        return workerPresenceStore;
    }
}
