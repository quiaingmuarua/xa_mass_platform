package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.starter.transport.ManagedTransportAdapter;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportBinding;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap<OutboundDelivery> {

    private final boolean enabled;
    private final boolean transportServerEnabled;
    private final int maxConnections;
    private final String transportEndpointPath;
    private final TransportServerFactory<TransportServerFactoryContext> transportServerFactory;

    public WebSocketTransportAdapterBootstrap(boolean enabled,
                                              boolean transportServerEnabled,
                                              int maxConnections,
                                              String transportEndpointPath,
                                              TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
        this.enabled = enabled;
        this.transportServerEnabled = transportServerEnabled;
        this.maxConnections = maxConnections;
        this.transportEndpointPath = transportEndpointPath;
        this.transportServerFactory = transportServerFactory;
    }

    @Override
    public TransportAdapterContribution create(TransportAdapterBootstrapContext<OutboundDelivery> context) {
        WebSocketDispatchRuntimeContext dispatcherContext = WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                context.getMessageTransporter(),
                context.getEndpointRegistry(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel()
        );

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (enabled) {
            contribution.transportBinding(TransportBinding.builder(
                    WebSocketEmbeddedRuntimeSupport.createRealtimeWorkerAdapter(
                            new WebSocketTaskDispatchChannel(dispatcherContext)
                    )
            ).build());
            contribution.rawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(
                    context.getEndpointRegistry(),
                    context.getMessageTransporter()
            ));
            ManagedTransportAdapter managedTransportAdapter =
                    new WebSocketManagedTransportAdapter(maxConnections, dispatcherContext);
            contribution.managedTransportAdapter(managedTransportAdapter);
        }

        if (transportServerEnabled) {
            TransportServer transportServer = transportServerFactory == null
                    ? WebSocketEmbeddedRuntimeSupport.createTransportServer(
                    transportEndpointPath,
                    dispatcherContext,
                    context.getEndpointRegistry()
            )
                    : transportServerFactory.create(new TransportServerFactoryContext(
                    context.getEndpointRegistry(),
                    context.getMessageTransporter()::sendInput,
                    context.getServerPort(),
                    transportEndpointPath
            ));
            contribution.transportServer(transportServer);
        }

        return contribution.build();
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.starter.transport.RawWorkerMessageChannel {

        private final com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry;
        private final com.xa.mass.base.channel.tranporter.MessageTransporter<String, OutboundDelivery> messageTransporter;

        private WebSocketRawWorkerMessageChannel(
                com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry,
                com.xa.mass.base.channel.tranporter.MessageTransporter<String, OutboundDelivery> messageTransporter) {
            this.endpointRegistry = endpointRegistry;
            this.messageTransporter = messageTransporter;
        }

        @Override
        public boolean supports(String workerId) {
            return workerId != null && !workerId.isBlank() && endpointRegistry.isWorkerOnline(workerId);
        }

        @Override
        public void send(String workerId, String rawJson, String traceId) {
            messageTransporter.sendOutput(new OutboundDelivery(workerId, rawJson, traceId));
        }
    }
}
