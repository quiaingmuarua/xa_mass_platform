package com.xa.mass.starter.transport;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.channel.TaskPullChannel;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * worker-facing transport channels and control-event publish support.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final TaskPullChannel taskPullChannel;
    private final WorkerControlEventPublisher workerControlEventPublisher;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.taskPullChannel = builder.taskPullChannel;
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

    public WorkerControlEventPublisher getWorkerControlEventPublisher() {
        return workerControlEventPublisher;
    }

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private TaskPullChannel taskPullChannel;
        private WorkerControlEventPublisher workerControlEventPublisher;

        private Builder(WorkerAdapter workerAdapter) {
            this.workerAdapter = workerAdapter;
        }

        public Builder taskPullChannel(TaskPullChannel taskPullChannel) {
            this.taskPullChannel = taskPullChannel;
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
