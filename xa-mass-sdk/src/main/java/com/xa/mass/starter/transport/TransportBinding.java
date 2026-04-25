package com.xa.mass.starter.transport;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.channel.TaskPullChannel;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * worker-facing transport channels.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final TaskPullChannel taskPullChannel;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.taskPullChannel = builder.taskPullChannel;
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

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private TaskPullChannel taskPullChannel;

        private Builder(WorkerAdapter workerAdapter) {
            this.workerAdapter = workerAdapter;
        }

        public Builder taskPullChannel(TaskPullChannel taskPullChannel) {
            this.taskPullChannel = taskPullChannel;
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }
}
