package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.socket.dispatcher.SocketTaskDispatchChannel;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import com.xa.mass.transport.socket.worker.SocketRealtimeWorkerAdapter;

/**
 * Adapter-owned bootstrap for embedded raw-socket runtime contribution.
 */
public final class SocketTransportAdapterBootstrap implements TransportAdapterBootstrap<TransportOutboundMessage> {

    private final SocketAdapterConfig config;

    public SocketTransportAdapterBootstrap(SocketAdapterConfig config) {
        this.config = new SocketAdapterConfig(config);
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
        SocketSessionManager sessionManager = resolveSessionManager(context);
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            contribution.transportBinding(TransportBinding.builder(
                    new SocketRealtimeWorkerAdapter(config.getAdapterId(), new SocketTaskDispatchChannel(
                            config.getAdapterId(),
                            sessionManager,
                            frameCodec,
                            context.getDeliveryService()
                    ))
            ).routeKeyResolver((dispatchBinding, routeContext) -> {
                if (routeContext != null && routeContext.workerId() != null && !routeContext.workerId().isBlank()) {
                    return routeContext.workerId();
                }
                return dispatchBinding != null ? dispatchBinding.workerId() : null;
            }).build());
            contribution.rawWorkerMessageChannel(new SocketRawWorkerMessageChannel(config.getAdapterId(), sessionManager));
        }
        if (config.isServerEnabled()) {
            contribution.transportServer(new SocketTransportServer(
                    config.getAdapterId(),
                    config.getBindHost(),
                    config.getServerPort(),
                    config.getMaxConnections(),
                    sessionManager,
                    frameCodec,
                    context.getTaskResultIngestChannel(),
                    context.getSystemEventChannel(),
                    context.getRuntimeTaskExecutor()
            ));
        }
        return contribution.build();
    }

    private SocketSessionManager resolveSessionManager(TransportAdapterBootstrapContext<TransportOutboundMessage> context) {
        if (context.getEndpointRegistry() instanceof SocketSessionManager sessionManager) {
            if (!config.getAdapterId().equalsIgnoreCase(sessionManager.getAdapterId())) {
                throw new IllegalStateException("Socket transport requires endpoint registry adapterId '"
                        + config.getAdapterId() + "' but found '" + sessionManager.getAdapterId() + "'");
            }
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            SocketSessionManager sessionManager = composite.getOrRegister(
                    config.getAdapterId(),
                    () -> new SocketSessionManager(config.getAdapterId(), context.getSystemEventChannel())
            );
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        throw new IllegalStateException("Socket transport requires a socket-managed endpoint registry");
    }

    private static final class SocketRawWorkerMessageChannel implements RawWorkerMessageChannel {

        private final String adapterId;
        private final SocketSessionManager sessionManager;

        private SocketRawWorkerMessageChannel(String adapterId, SocketSessionManager sessionManager) {
            this.adapterId = adapterId;
            this.sessionManager = sessionManager;
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
                    && sessionManager.isAdapterRouteOnline(adapterId(), routeKey);
        }

        @Override
        public void sendToAdapterRoute(String routeKey, String rawJson, String traceId) {
            sessionManager.sendToAdapterRoute(adapterId(), routeKey, rawJson);
        }
    }
}

