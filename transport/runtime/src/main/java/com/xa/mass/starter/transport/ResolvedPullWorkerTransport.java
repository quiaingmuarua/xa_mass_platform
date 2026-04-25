package com.xa.mass.starter.transport;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.Objects;

/**
 * Runtime-owned pull transport binding resolved for one worker.
 */
public final class ResolvedPullWorkerTransport {

    private final String workerId;
    private final String transportHint;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;

    public ResolvedPullWorkerTransport(String workerId,
                                       String transportHint,
                                       TaskPullChannel taskPullChannel,
                                       TaskResultIngestChannel taskResultIngestChannel,
                                       WorkerSystemEventChannel systemEventChannel) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.transportHint = Objects.requireNonNull(transportHint, "transportHint");
        this.taskPullChannel = Objects.requireNonNull(taskPullChannel, "taskPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
    }

    public String getWorkerId() {
        return workerId;
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
}
