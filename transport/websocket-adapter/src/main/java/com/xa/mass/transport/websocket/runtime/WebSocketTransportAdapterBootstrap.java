package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketCommandDispatchContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;
import com.xa.mass.transport.websocket.frame.WebSocketSessionOpenFrameReader;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.WebSocketEndpointInspector;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.transport.websocket.session.WebSocketRawWorkerRouteEndpointRegistry;

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
    public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
        ServerSessionManager endpointRegistry = resolveEndpointRegistry(context);
        WebSocketRawWorkerRouteEndpointRegistry rawRouteEndpointRegistry =
                new WebSocketRawWorkerRouteEndpointRegistry(config.getAdapterId(), endpointRegistry);
        WebSocketJsonFrameParser frameParser = new WebSocketJsonFrameParser();
        WebSocketResultIngressFrameReader resultFrameReader =
                new WebSocketResultIngressFrameReader(config.getAdapterId(), frameParser);
        WebSocketSessionOpenFrameReader sessionOpenFrameReader =
                new WebSocketSessionOpenFrameReader(frameParser);
        WebSocketCommandDispatchContext commandContext = new WebSocketCommandDispatchContext(
                config.getAdapterId(),
                endpointRegistry
        );
        WebSocketDispatcherContext dispatcherContext = new WebSocketDispatcherContext(
                config.getAdapterId(),
                rawRouteEndpointRegistry,
                frameParser,
                resultFrameReader,
                context.getResultIngressChannel()
        );

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            WebSocketTaskDispatchChannel commandExecutor =
                    new WebSocketTaskDispatchChannel(commandContext, context.getDeliveryService());
            contribution.addTransportBinding(TransportBinding.builder(
                            config.getAdapterId(),
                            com.xa.mass.transport.WorkerTransportHints.REALTIME,
                            commandExecutor
                    )
                    .protocol(WebSocketAdapterConfig.PROTOCOL)
                    .build());
            contribution.addRawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(
                    config.getAdapterId(),
                    rawRouteEndpointRegistry
            ));
            contribution.addEndpointInspector(new WebSocketEndpointInspector(endpointRegistry));
        }

        TransportServer transportServer = createTransportServer(
                dispatcherContext,
                sessionOpenFrameReader,
                endpointRegistry
        );
        if (transportServer != null) {
            contribution.addTransportServer(transportServer);
        }
        return contribution.build();
    }

    private ServerSessionManager resolveEndpointRegistry(
            TransportAdapterBootstrapContext context) {
        if (context.getEndpointRegistry() instanceof ServerSessionManager sessionManager) {
            if (!config.getAdapterId().equalsIgnoreCase(sessionManager.getAdapterId())) {
                throw new IllegalStateException("WebSocket transport requires endpoint registry adapterId '"
                        + config.getAdapterId() + "' but found '" + sessionManager.getAdapterId() + "'");
            }
            sessionManager.setEndpointLeaseStore(context.getEndpointLeaseStore());
            sessionManager.setDeliveryCommandConsumerRegistry(context.getDeliveryCommandConsumerRegistry());
            sessionManager.setWorkerPresenceIngress(context.getWorkerPresenceIngress());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            ServerSessionManager sessionManager =
                    composite.getOrRegister(
                            config.getAdapterId(),
                            () -> new ServerSessionManager(config.getAdapterId())
            );
            sessionManager.setEndpointLeaseStore(context.getEndpointLeaseStore());
            sessionManager.setDeliveryCommandConsumerRegistry(context.getDeliveryCommandConsumerRegistry());
            sessionManager.setWorkerPresenceIngress(context.getWorkerPresenceIngress());
            return sessionManager;
        }
        throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
    }

    private TransportServer createTransportServer(WebSocketDispatcherContext dispatcherContext,
                                                  WebSocketSessionOpenFrameReader sessionOpenFrameReader,
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
                dispatcherContext.getFrameParser(),
                sessionOpenFrameReader,
                new WebSocketInputProcessor(dispatcherContext)::process,
                sessionManager
        );
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.transport.runtime.RawWorkerMessageChannel {

        private final String adapterId;
        private final com.xa.mass.transport.RawWorkerRouteEndpointRegistry endpointRegistry;

        private WebSocketRawWorkerMessageChannel(
                String adapterId,
                com.xa.mass.transport.RawWorkerRouteEndpointRegistry endpointRegistry) {
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

