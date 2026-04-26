package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.starter.transport.ManagedTransportAdapter;
import com.xa.mass.starter.transport.CompositeWorkerEndpointRegistry;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportBinding;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor;
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
        com.xa.mass.transport.websocket.session.ServerSessionManager endpointRegistry =
                resolveEndpointRegistry(context);
        WebSocketDispatchRuntimeContext dispatcherContext = WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                endpointRegistry,
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
            contribution.rawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(endpointRegistry));
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
                    endpointRegistry
            )
                    : transportServerFactory.create(new TransportServerFactoryContext(
                    endpointRegistry,
                    new WebSocketInputProcessor(dispatcherContext)::process,
                    config.getServerPort(),
                    config.getEndpointPath()
            ));
            contribution.transportServer(transportServer);
        }

        return contribution.build();
    }

    private com.xa.mass.transport.websocket.session.ServerSessionManager resolveEndpointRegistry(
            TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
        if (context.getEndpointRegistry() instanceof com.xa.mass.transport.websocket.session.ServerSessionManager sessionManager) {
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            com.xa.mass.transport.websocket.session.ServerSessionManager sessionManager =
                    composite.getOrRegister(
                            com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter.PROTOCOL,
                            com.xa.mass.transport.websocket.session.ServerSessionManager::new
                    );
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.starter.transport.RawWorkerMessageChannel {

        private final com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry;

        private WebSocketRawWorkerMessageChannel(
                com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry) {
            this.endpointRegistry = endpointRegistry;
        }

        @Override
        public String adapterId() {
            return com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter.PROTOCOL;
        }

        @Override
        public boolean supports(String workerId, String workerAdapterId) {
            return adapterId().equalsIgnoreCase(workerAdapterId == null ? "" : workerAdapterId.trim())
                    && workerId != null
                    && !workerId.isBlank()
                    && endpointRegistry.isWorkerOnline(workerId);
        }

        @Override
        public void send(String workerId, String rawJson, String traceId) {
            endpointRegistry.sendMessage(workerId, rawJson);
        }
    }
}
