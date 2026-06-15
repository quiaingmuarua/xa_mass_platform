package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.worker.WorkerAdapter;

import java.util.Objects;

/**
 * Runtime binding for a concrete worker transport adapter plus any optional
 * worker-facing transport channels.
 */
public final class TransportBinding {

    private final WorkerAdapter workerAdapter;
    private final DeliveryPullChannel deliveryPullChannel;

    private TransportBinding(Builder builder) {
        this.workerAdapter = Objects.requireNonNull(builder.workerAdapter, "workerAdapter");
        this.deliveryPullChannel = builder.deliveryPullChannel;
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

    public DeliveryPullChannel getDeliveryPullChannel() {
        return deliveryPullChannel;
    }

    public static final class Builder {
        private final WorkerAdapter workerAdapter;
        private DeliveryPullChannel deliveryPullChannel;

        private Builder(WorkerAdapter workerAdapter) {
            this.workerAdapter = workerAdapter;
        }

        public Builder deliveryPullChannel(DeliveryPullChannel deliveryPullChannel) {
            this.deliveryPullChannel = deliveryPullChannel;
            return this;
        }

        public TransportBinding build() {
            return new TransportBinding(this);
        }
    }
}
