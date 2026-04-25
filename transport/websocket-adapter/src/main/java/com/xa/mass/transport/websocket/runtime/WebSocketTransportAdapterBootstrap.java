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

    private final WebSocketAdapterConfig config;

    public WebSocketTransportAdapterBootstrap(WebSocketAdapterConfig config) {
        this.config = new WebSocketAdapterConfig(config);
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
        if (config.isEnabled()) {
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
                    new WebSocketManagedTransportAdapter(config.getMaxConnections(), dispatcherContext);
            contribution.managedTransportAdapter(managedTransportAdapter);
        }

        if (config.isServerEnabled()) {
            TransportServerFactory<TransportServerFactoryContext> transportServerFactory =
                    config.getTransportServerFactory();
            TransportServer transportServer = transportServerFactory == null
                    ? WebSocketEmbeddedRuntimeSupport.createTransportServer(
                    config.getServerPort(),
                    config.getEndpointPath(),
                    dispatcherContext,
                    context.getEndpointRegistry()
            )
                    : transportServerFactory.create(new TransportServerFactoryContext(
                    context.getEndpointRegistry(),
                    context.getMessageTransporter()::sendInput,
                    config.getServerPort(),
                    config.getEndpointPath()
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
