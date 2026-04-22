package com.xa.mass.starter.transport;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;

import java.util.List;
import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * pull/result channels and inbound handler registrations it contributes.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final List<TransportInboundRoute> inboundRoutes;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.taskPullChannel = builder.taskPullChannel;
        this.taskResultIngestChannel = builder.taskResultIngestChannel;
        this.inboundRoutes = List.copyOf(builder.inboundRoutes);
    }

    public static Builder builder(WorkerAdapter workerAdapter) {
        return new Builder(workerAdapter);
    }

    public WorkerAdapter getWorkerAdapter() {
        return workerAdapter;
    }

    public TaskPullChannel getTaskPullChannel() {
        return taskPullChannel;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public List<TransportInboundRoute> getInboundRoutes() {
        return inboundRoutes;
    }

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private TaskPullChannel taskPullChannel;
        private TaskResultIngestChannel taskResultIngestChannel;
        private List<TransportInboundRoute> inboundRoutes = List.of();

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

        public Builder inboundRoutes(List<TransportInboundRoute> inboundRoutes) {
            this.inboundRoutes = inboundRoutes != null ? inboundRoutes : List.of();
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }
}
