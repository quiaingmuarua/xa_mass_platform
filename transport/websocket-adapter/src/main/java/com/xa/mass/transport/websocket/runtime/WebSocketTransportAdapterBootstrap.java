package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap<TransportOutboundMessage> {

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
    public TransportAdapterContribution create(TransportAdapterBootstrapContext<TransportOutboundMessage> context) {
        com.xa.mass.transport.websocket.session.ServerSessionManager endpointRegistry =
                resolveEndpointRegistry(context);
        WebSocketDispatchRuntimeContext dispatcherContext = WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                config.getAdapterId(),
                endpointRegistry,
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel()
        );

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            contribution.transportBinding(TransportBinding.builder(
                    WebSocketEmbeddedRuntimeSupport.createRealtimeWorkerAdapter(
                            config.getAdapterId(),
                            new WebSocketTaskDispatchChannel(dispatcherContext, context.getDeliveryService())
                    )
            ).routeKeyResolver((dispatchBinding, routeContext) -> {
                if (routeContext != null && routeContext.workerId() != null && !routeContext.workerId().isBlank()) {
                    return routeContext.workerId();
                }
                return dispatchBinding != null ? dispatchBinding.workerId() : null;
            }).build());
            contribution.rawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(config.getAdapterId(), endpointRegistry));
        }

        TransportServer transportServer = WebSocketEmbeddedRuntimeSupport.createTransportServer(
                config,
                dispatcherContext,
                endpointRegistry
        );
        if (transportServer != null) {
            contribution.transportServer(transportServer);
        }

        return contribution.build();
    }

    private com.xa.mass.transport.websocket.session.ServerSessionManager resolveEndpointRegistry(
            TransportAdapterBootstrapContext<TransportOutboundMessage> context) {
        if (context.getEndpointRegistry() instanceof com.xa.mass.transport.websocket.session.ServerSessionManager sessionManager) {
            if (!config.getAdapterId().equalsIgnoreCase(sessionManager.getAdapterId())) {
                throw new IllegalStateException("WebSocket transport requires endpoint registry adapterId '"
                        + config.getAdapterId() + "' but found '" + sessionManager.getAdapterId() + "'");
            }
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            com.xa.mass.transport.websocket.session.ServerSessionManager sessionManager =
                    composite.getOrRegister(
                            config.getAdapterId(),
                            () -> new com.xa.mass.transport.websocket.session.ServerSessionManager(config.getAdapterId())
                    );
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
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
        public boolean supportsAdapterRoute(String routeKey, String workerAdapterId) {
            return workerAdapterId != null
                    && adapterId() != null
                    && adapterId().equalsIgnoreCase(workerAdapterId.trim())
                    && routeKey != null
                    && !routeKey.isBlank()
                    && endpointRegistry.isAdapterRouteOnline(adapterId(), routeKey);
        }

        @Override
        public void sendToAdapterRoute(String routeKey, String rawJson, String traceId) {
            endpointRegistry.sendToAdapterRoute(adapterId(), routeKey, rawJson);
        }
    }
}

