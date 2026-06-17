package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.socket.dispatcher.SocketCommandDispatchContext;
import com.xa.mass.transport.socket.dispatcher.SocketTaskDispatchChannel;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import com.xa.mass.transport.socket.session.SocketEndpointInspector;
import com.xa.mass.transport.socket.session.SocketRawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.socket.session.SocketSessionManager;

/**
 * Adapter-owned bootstrap for embedded raw-socket runtime contribution.
 */
public final class SocketTransportAdapterBootstrap implements TransportAdapterBootstrap {

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
    public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
        SocketSessionManager sessionManager = resolveSessionManager(context);
        SocketRawWorkerRouteEndpointRegistry rawRouteEndpointRegistry =
                new SocketRawWorkerRouteEndpointRegistry(config.getAdapterId(), sessionManager);
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();
        SocketCommandDispatchContext commandContext = new SocketCommandDispatchContext(
                config.getAdapterId(),
                sessionManager,
                frameCodec
        );

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            SocketTaskDispatchChannel commandExecutor = new SocketTaskDispatchChannel(
                            commandContext,
                            context.getDeliveryService()
                    );
            contribution.addTransportBinding(TransportBinding.builder(
                            config.getAdapterId(),
                            com.xa.mass.transport.WorkerTransportHints.REALTIME,
                            commandExecutor
                    )
                    .protocol(SocketAdapterConfig.PROTOCOL)
                    .build());
            contribution.addRawWorkerMessageChannel(new SocketRawWorkerMessageChannel(
                    config.getAdapterId(),
                    rawRouteEndpointRegistry
            ));
            contribution.addEndpointInspector(new SocketEndpointInspector(sessionManager));
        }
        if (config.isServerEnabled()) {
            contribution.addTransportServer(new SocketTransportServer(
                    config.getAdapterId(),
                    config.getBindHost(),
                    config.getServerPort(),
                    config.getMaxConnections(),
                    sessionManager,
                    frameCodec,
                    context.getResultIngressChannel(),
                    context.getRuntimeTaskExecutor()
            ));
        }
        return contribution.build();
    }

    private SocketSessionManager resolveSessionManager(TransportAdapterBootstrapContext context) {
        if (context.getEndpointRegistry() instanceof SocketSessionManager sessionManager) {
            if (!config.getAdapterId().equalsIgnoreCase(sessionManager.getAdapterId())) {
                throw new IllegalStateException("Socket transport requires endpoint registry adapterId '"
                        + config.getAdapterId() + "' but found '" + sessionManager.getAdapterId() + "'");
            }
            sessionManager.setEndpointLeaseStore(context.getEndpointLeaseStore());
            sessionManager.setDeliveryCommandConsumerRegistry(context.getDeliveryCommandConsumerRegistry());
            sessionManager.setWorkerPresenceIngress(context.getWorkerPresenceIngress());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            SocketSessionManager sessionManager = composite.getOrRegister(
                    config.getAdapterId(),
                    () -> new SocketSessionManager(config.getAdapterId())
            );
            sessionManager.setEndpointLeaseStore(context.getEndpointLeaseStore());
            sessionManager.setDeliveryCommandConsumerRegistry(context.getDeliveryCommandConsumerRegistry());
            sessionManager.setWorkerPresenceIngress(context.getWorkerPresenceIngress());
            return sessionManager;
        }
        throw new IllegalStateException("Socket transport requires a socket-managed endpoint registry");
    }

    private static final class SocketRawWorkerMessageChannel implements RawWorkerMessageChannel {

        private final String adapterId;
        private final com.xa.mass.transport.RawWorkerRouteEndpointRegistry sessionManager;

        private SocketRawWorkerMessageChannel(
                String adapterId,
                com.xa.mass.transport.RawWorkerRouteEndpointRegistry sessionManager) {
            this.adapterId = adapterId;
            this.sessionManager = sessionManager;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public void sendToAdapterRoute(String routeKey, String rawJson, String traceId) {
            sessionManager.sendToAdapterRoute(adapterId(), routeKey, rawJson);
        }
    }
}

