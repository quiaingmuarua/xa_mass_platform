package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.worker.PollingDeliveryExecutor;
import com.xa.mass.transport.polling.worker.PollingDeliveryPullChannel;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Adapter-owned bootstrap for the bundled embedded polling runtime contribution.
 */
public final class PollingTransportAdapterBootstrap implements TransportAdapterBootstrap {

    public static final String DEFAULT_ADAPTER_ID = "polling-default";
    public static final String PROTOCOL = "polling";

    private final String adapterId;
    private final Supplier<PollingPendingDeliveryBuffer> pendingDeliveryBufferFactory;

    public PollingTransportAdapterBootstrap() {
        this(DEFAULT_ADAPTER_ID);
    }

    public PollingTransportAdapterBootstrap(String adapterId) {
        this(adapterId, InMemoryPollingPendingDeliveryBuffer::new);
    }

    public PollingTransportAdapterBootstrap(String adapterId,
                                            Supplier<PollingPendingDeliveryBuffer> pendingDeliveryBufferFactory) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
        this.pendingDeliveryBufferFactory = Objects.requireNonNull(
                pendingDeliveryBufferFactory,
                "pendingDeliveryBufferFactory"
        );
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return new TransportAdapterDescriptor(adapterId, WorkerTransportHints.POLLING);
    }

    @Override
    public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
        PollingAdapterMetadata metadata = new PollingAdapterMetadata(
                adapterId,
                PROTOCOL,
                WorkerTransportHints.POLLING
        );
        String adapterMailboxKey = context.adapterMailboxKey(metadata.adapterId());
        PollingPendingDeliveryBuffer pendingDeliveryBuffer = Objects.requireNonNull(
                pendingDeliveryBufferFactory.get(),
                "pendingDeliveryBufferFactory.get"
        );
        PollingSessionEvidenceDriver sessionEvidenceDriver = new PollingSessionEvidenceDriver(
                context.sessionEvidencePublisher(metadata.adapterId(), adapterMailboxKey)
        );
        PollingDeliveryExecutor deliveryExecutor = new PollingDeliveryExecutor(
                adapterMailboxKey,
                pendingDeliveryBuffer
        );
        PollingDeliveryPullChannel pullChannel = new PollingDeliveryPullChannel(
                adapterMailboxKey,
                pendingDeliveryBuffer
        );
        TransportBinding binding = TransportBinding.builder(
                        metadata.adapterId(),
                        metadata.transportHint()
                )
                .adapterMailboxKey(adapterMailboxKey)
                .protocol(metadata.protocol())
                .deliveryPullChannel(pullChannel)
                .pullSessionEvidenceDriver(sessionEvidenceDriver)
                .build();
        return TransportAdapterContribution.builder()
                .addTransportBinding(binding)
                .addAdapterMailboxConsumer(context.adapterMailboxConsumer(
                        adapterMailboxKey,
                        metadata.adapterId(),
                        deliveryExecutor
                ))
                .addManagedTransportAdapter(new PollingPendingDeliveryBufferHandle(pendingDeliveryBuffer))
                .build();
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
