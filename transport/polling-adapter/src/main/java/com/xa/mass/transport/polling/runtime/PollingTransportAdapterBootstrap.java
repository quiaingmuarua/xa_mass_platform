package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;

/**
 * Adapter-owned bootstrap for the bundled embedded polling runtime contribution.
 */
public final class PollingTransportAdapterBootstrap implements TransportAdapterBootstrap {

    public static final String DEFAULT_ADAPTER_ID = "polling-default";
    public static final String PROTOCOL = "polling";

    private final String adapterId;

    public PollingTransportAdapterBootstrap() {
        this(DEFAULT_ADAPTER_ID);
    }

    public PollingTransportAdapterBootstrap(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return new TransportAdapterDescriptor(adapterId, WorkerTransportHints.POLLING);
    }

    @Override
    public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(
                context.getEndpointLeaseStore(),
                context.getDeliveryService(),
                context.getDeliveryCommandConsumerRegistry(),
                adapterId
        );
        TransportBinding binding = TransportBinding.builder(
                        adapterId,
                        WorkerTransportHints.POLLING,
                        pollingAdapter
                )
                .protocol(PROTOCOL)
                .deliveryPullChannel(pollingAdapter)
                .build();
        return TransportAdapterContribution.builder()
                .addTransportBinding(binding)
                .build();
    }
}
