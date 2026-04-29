package com.xa.mass.transport.runtime;

import com.xa.mass.engine.listener.TaskDispatchBinding;
import com.xa.mass.runtime.apier.WorkerAdapter;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.channel.TaskPullChannel;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * worker-facing transport channels.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final TaskPullChannel taskPullChannel;
    private final TransportRouteKeyResolver routeKeyResolver;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.taskPullChannel = builder.taskPullChannel;
        this.routeKeyResolver = Objects.requireNonNull(builder.routeKeyResolver, "routeKeyResolver");
    }

    public static Builder builder(WorkerAdapter workerAdapter) {
        return new Builder(workerAdapter);
    }

    public WorkerAdapter getWorkerAdapter() {
        return workerAdapter;
    }

    public String getAdapterId() {
        return workerAdapter.adapterId();
    }

    public String getTransportHint() {
        return workerAdapter.transportHint();
    }

    public TaskPullChannel getTaskPullChannel() {
        return taskPullChannel;
    }

    public String resolveRouteKey(TaskDispatchBinding dispatchBinding, TaskDispatchItem payload) {
        return routeKeyResolver.resolveRouteKey(dispatchBinding, payload);
    }

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private TaskPullChannel taskPullChannel;
        private TransportRouteKeyResolver routeKeyResolver = TransportRouteKeyResolvers.workerId();

        private Builder(WorkerAdapter workerAdapter) {
            this.workerAdapter = workerAdapter;
        }

        public Builder taskPullChannel(TaskPullChannel taskPullChannel) {
            this.taskPullChannel = taskPullChannel;
            return this;
        }

        public Builder routeKeyResolver(TransportRouteKeyResolver routeKeyResolver) {
            this.routeKeyResolver = Objects.requireNonNull(routeKeyResolver, "routeKeyResolver");
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }
}

