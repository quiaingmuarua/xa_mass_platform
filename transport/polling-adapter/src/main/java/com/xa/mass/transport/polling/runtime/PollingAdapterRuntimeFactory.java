package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.worker.PollingDeliveryExecutor;
import com.xa.mass.transport.polling.worker.PollingDeliveryPullChannel;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterDispatchQueueConsumerLoop;
import com.xa.mass.transport.runtime.embedded.CompositeEmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Factory for embedded polling adapter runtimes.
 */
public final class PollingAdapterRuntimeFactory implements EmbeddedTransportAdapterRuntimeFactory {

    public static final String TYPE = "polling";
    public static final String DEFAULT_ADAPTER_ID = "polling-default";
    public static final String PROTOCOL = "polling";

    private final Supplier<PollingPendingDeliveryBuffer> pendingDeliveryBufferFactory;

    public PollingAdapterRuntimeFactory() {
        this(InMemoryPollingPendingDeliveryBuffer::new);
    }

    public PollingAdapterRuntimeFactory(Supplier<PollingPendingDeliveryBuffer> pendingDeliveryBufferFactory) {
        this.pendingDeliveryBufferFactory = Objects.requireNonNull(
                pendingDeliveryBufferFactory,
                "pendingDeliveryBufferFactory"
        );
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TransportAdapterDescriptor descriptor(EmbeddedAdapterRuntimeSpec spec) {
        return new TransportAdapterDescriptor(spec.adapterId(), WorkerTransportHints.POLLING);
    }

    @Override
    public EmbeddedTransportAdapterRuntime create(EmbeddedAdapterRuntimeSpec spec,
                                                  EmbeddedAdapterRuntimeEnvironment environment) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        TransportAdapterDescriptor descriptor = descriptor(spec);
        AdapterSessionEvidencePublisher sessionEvidencePublisher = new AdapterSessionEvidencePublisher(
                descriptor.getAdapterId(),
                spec.dispatchQueueKey(),
                environment.endpointLeaseStore(),
                environment.currentSessionConnectSink(),
                environment.currentSessionDisconnectSink()
        );
        PollingPendingDeliveryBuffer pendingDeliveryBuffer = Objects.requireNonNull(
                pendingDeliveryBufferFactory.get(),
                "pendingDeliveryBufferFactory.get"
        );
        PollingSessionEvidenceDriver sessionEvidenceDriver = new PollingSessionEvidenceDriver(
                sessionEvidencePublisher
        );
        PollingDeliveryExecutor deliveryExecutor = new PollingDeliveryExecutor(
                spec.dispatchQueueKey(),
                pendingDeliveryBuffer
        );
        PollingDeliveryPullChannel pullChannel = new PollingDeliveryPullChannel(
                spec.dispatchQueueKey(),
                pendingDeliveryBuffer
        );
        AdapterDispatchQueueConsumerLoop dispatchConsumer = new AdapterDispatchQueueConsumerLoop(
                spec.dispatchQueueKey(),
                environment.dispatchQueue(),
                deliveryExecutor,
                environment.deliveryFailureHandler(),
                environment.executor()
        );
        TransportBinding binding = TransportBinding.builder(descriptor.getAdapterId(), descriptor.getTransportHint())
                .adapterMailboxKey(spec.dispatchQueueKey())
                .protocol(PROTOCOL)
                .deliveryPullChannel(pullChannel)
                .pullSessionEvidenceDriver(sessionEvidenceDriver)
                .build();
        return new CompositeEmbeddedTransportAdapterRuntime(
                descriptor,
                binding,
                List.of(dispatchConsumer, new PollingPendingDeliveryBufferHandle(pendingDeliveryBuffer)),
                List.of()
        );
    }

    private static final class PollingPendingDeliveryBufferHandle implements ManagedTransportAdapter {

        private final PollingPendingDeliveryBuffer buffer;
        private final AtomicBoolean running = new AtomicBoolean();

        private PollingPendingDeliveryBufferHandle(PollingPendingDeliveryBuffer buffer) {
            this.buffer = Objects.requireNonNull(buffer, "buffer");
        }

        @Override
        public void start() {
            running.set(true);
        }

        @Override
        public void stop() {
            if (running.getAndSet(false)) {
                buffer.shutdown();
            }
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }
    }
}
