package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportRouteKeyResolvers;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap {

    private final WebSocketAdapterConfig config;

    public WebSocketTransportAdapterBootstrap(WebSocketAdapterConfig config) {
        this.config = new WebSocketAdapterConfig(config);
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return new TransportAdapterDescriptor(
                config.getAdapterId(),
                com.xa.mass.transport.WorkerTransportHints.REALTIME
        );
    }

    @Override
    public void contribute(TransportAdapterBootstrapContext context) {
        ServerSessionManager endpointRegistry = resolveEndpointRegistry(context);
        WebSocketDispatcherContext dispatcherContext = new WebSocketDispatcherContext(
                config.getAdapterId(),
                endpointRegistry,
                new WebSocketTransportFrameCodec(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel()
        );

        if (config.isEnabled()) {
            context.registerTransportBinding(TransportBinding.builder(
                    new WebSocketRealtimeWorkerAdapter(
                            config.getAdapterId(),
                            new WebSocketTaskDispatchChannel(dispatcherContext, context.getDeliveryService())
                    )
            ).routeKeyResolver(TransportRouteKeyResolvers.canonicalWorkerSubject()).build());
            context.registerRawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(config.getAdapterId(), endpointRegistry));
        }

        TransportServer transportServer = createTransportServer(dispatcherContext, endpointRegistry);
        if (transportServer != null) {
            context.registerTransportServer(transportServer);
        }
    }

    private ServerSessionManager resolveEndpointRegistry(
            TransportAdapterBootstrapContext context) {
        if (context.getEndpointRegistry() instanceof ServerSessionManager sessionManager) {
            if (!config.getAdapterId().equalsIgnoreCase(sessionManager.getAdapterId())) {
                throw new IllegalStateException("WebSocket transport requires endpoint registry adapterId '"
                        + config.getAdapterId() + "' but found '" + sessionManager.getAdapterId() + "'");
            }
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            sessionManager.setWorkerPresenceStore(context.getWorkerPresenceStore());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            ServerSessionManager sessionManager =
                    composite.getOrRegister(
                            config.getAdapterId(),
                            () -> new ServerSessionManager(config.getAdapterId())
                    );
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            sessionManager.setWorkerPresenceStore(context.getWorkerPresenceStore());
            return sessionManager;
        }
        throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
    }

    private TransportServer createTransportServer(WebSocketDispatcherContext dispatcherContext,
                                                  ServerSessionManager sessionManager) {
        if (!config.isServerEnabled()) {
            return null;
        }
        TransportServerFactory<TransportServerFactoryContext> transportServerFactory =
                config.getTransportServerFactory();
        if (transportServerFactory != null) {
            return transportServerFactory.create(new TransportServerFactoryContext(
                    sessionManager,
                    new WebSocketInputProcessor(dispatcherContext)::process,
                    config.getServerPort(),
                    config.getEndpointPath()
            ));
        }
        return new WebSocketServerImpl(
                config.getServerPort(),
                config.getMaxConnections(),
                config.getEndpointPath(),
                dispatcherContext.getFrameCodec(),
                new WebSocketInputProcessor(dispatcherContext)::process,
                sessionManager
        );
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.transport.runtime.RawWorkerMessageChannel {

        private final String adapterId;
        private final com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry;

        private WebSocketRawWorkerMessageChannel(
                String adapterId,
                com.xa.mass.transport.WorkerEndpointRegistry endpointRegistry) {
            this.adapterId = adapterId;
            this.endpointRegistry = endpointRegistry;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public void sendToAdapterRoute(String routeKey, String rawJson, String traceId) {
            endpointRegistry.sendToAdapterRoute(adapterId(), routeKey, rawJson);
        }
    }
}

