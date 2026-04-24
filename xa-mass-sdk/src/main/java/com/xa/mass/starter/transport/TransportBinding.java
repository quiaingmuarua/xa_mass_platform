package com.xa.mass.starter.transport;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * pull/result channels and any explicit gateway adapter bridges it contributes.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final TaskStepFrameBridge taskStepFrameBridge;
    private final WorkerControlEventPublisher workerControlEventPublisher;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.taskPullChannel = builder.taskPullChannel;
        this.taskResultIngestChannel = builder.taskResultIngestChannel;
        this.taskStepFrameBridge = builder.taskStepFrameBridge;
        this.workerControlEventPublisher = builder.workerControlEventPublisher;
    }

    public static Builder builder(WorkerAdapter workerAdapter) {
        return new Builder(workerAdapter);
    }

    public WorkerAdapter getWorkerAdapter() {
        return workerAdapter;
    }

    public String getTransportHint() {
        return workerAdapter.transportHint();
    }

    public TaskPullChannel getTaskPullChannel() {
        return taskPullChannel;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public TaskStepFrameBridge getTaskStepFrameBridge() {
        return taskStepFrameBridge;
    }

    public WorkerControlEventPublisher getWorkerControlEventPublisher() {
        return workerControlEventPublisher;
    }

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private TaskPullChannel taskPullChannel;
        private TaskResultIngestChannel taskResultIngestChannel;
        private TaskStepFrameBridge taskStepFrameBridge;
        private WorkerControlEventPublisher workerControlEventPublisher;

        private Builder(WorkerAdapter workerAdapter) {
            this.workerAdapter = workerAdapter;
        }

        public Builder taskPullChannel(TaskPullChannel taskPullChannel) {
            this.taskPullChannel = taskPullChannel;
            return this;
        }

        public Builder taskResultIngestChannel(TaskResultIngestChannel taskResultIngestChannel) {
            this.taskResultIngestChannel = taskResultIngestChannel;
            return this;
        }

        public Builder taskStepFrameBridge(TaskStepFrameBridge taskStepFrameBridge) {
            this.taskStepFrameBridge = taskStepFrameBridge;
            return this;
        }

        public Builder workerControlEventPublisher(WorkerControlEventPublisher workerControlEventPublisher) {
            this.workerControlEventPublisher = workerControlEventPublisher;
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }
}
