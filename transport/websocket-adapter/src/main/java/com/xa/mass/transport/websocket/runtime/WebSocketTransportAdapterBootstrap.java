package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.starter.transport.ManagedTransportAdapter;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportBinding;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap<WorkerTransportMessage> {

    private final boolean enabled;
    private final boolean transportServerEnabled;
    private final int transportServerPort;
    private final int maxConnections;
    private final String transportEndpointPath;
    private final TransportServerFactory<TransportServerFactoryContext> transportServerFactory;

    public WebSocketTransportAdapterBootstrap(boolean enabled,
                                              boolean transportServerEnabled,
                                              int transportServerPort,
                                              int maxConnections,
                                              String transportEndpointPath,
                                              TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
        this.enabled = enabled;
        this.transportServerEnabled = transportServerEnabled;
        this.transportServerPort = transportServerPort;
        this.maxConnections = maxConnections;
        this.transportEndpointPath = transportEndpointPath;
        this.transportServerFactory = transportServerFactory;
    }

    @Override
    public TransportAdapterContribution create(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
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
                    transportServerPort,
                    transportEndpointPath,
                    dispatcherContext,
                    context.getEndpointRegistry()
            )
                    : transportServerFactory.create(new TransportServerFactoryContext(
                    context.getEndpointRegistry(),
                    context.getMessageTransporter()::sendInput,
                    transportServerPort,
                    transportEndpointPath
            ));
            contribution.transportServer(transportServer);
        }

        return contribution.build();
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.starter.transport.RawWorkerMessageChannel {

        private final com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry;
        private final com.xa.mass.base.channel.tranporter.MessageTransporter<String, WorkerTransportMessage> messageTransporter;

        private WebSocketRawWorkerMessageChannel(
                com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry,
                com.xa.mass.base.channel.tranporter.MessageTransporter<String, WorkerTransportMessage> messageTransporter) {
            this.endpointRegistry = endpointRegistry;
            this.messageTransporter = messageTransporter;
        }

        @Override
        public boolean supports(String workerId) {
            return workerId != null && !workerId.isBlank() && endpointRegistry.isWorkerOnline(workerId);
        }

        @Override
        public void send(String workerId, String rawJson, String traceId) {
            messageTransporter.sendOutput(new WorkerTransportMessage(workerId, rawJson, traceId));
        }
    }
}
