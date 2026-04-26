package com.xa.mass.transport.runtime;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.Objects;

/**
 * Transport-neutral runtime inputs handed to adapter-owned bootstrap code.
 */
public final class TransportAdapterBootstrapContext<T> {

    private final MessageTransporter<String, T> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final TransportDeliveryService deliveryService;

    public TransportAdapterBootstrapContext(MessageTransporter<String, T> messageTransporter,
                                            WorkerEndpointRegistry endpointRegistry,
                                            TaskResultIngestChannel taskResultIngestChannel,
                                            WorkerSystemEventChannel systemEventChannel,
                                            TransportDeliveryService deliveryService) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    /**
     * Compatibility-only transporter handle.
     *
     * <p>Adapter-owned mainline runtime assembly should prefer adapter-native
     * server/session paths and canonical runtime channels. This value may be
     * {@code null} when the runtime is assembled without shared queue-backed
     * transporter wiring.
     */
    public MessageTransporter<String, T> getMessageTransporter() {
        return messageTransporter;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }

    public TransportDeliveryService getDeliveryService() {
        return deliveryService;
    }
}
