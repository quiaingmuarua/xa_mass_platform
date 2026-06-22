package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
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
        String adapterMailboxKey = context.mailbox().assignedMailboxKey();
        SocketSessionManager sessionManager = resolveSessionManager(context, adapterMailboxKey);
        SocketRawWorkerRouteEndpointRegistry rawRouteEndpointRegistry =
                new SocketRawWorkerRouteEndpointRegistry(config.getAdapterId(), sessionManager);
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            SocketTaskDispatchChannel commandExecutor = new SocketTaskDispatchChannel(
                            frameCodec,
                            sessionManager
                    );
            contribution.addTransportBinding(TransportBinding.builder(
                            config.getAdapterId(),
                            com.xa.mass.transport.WorkerTransportHints.REALTIME
                    )
                    .adapterMailboxKey(adapterMailboxKey)
                    .protocol(SocketAdapterConfig.PROTOCOL)
                    .build());
            contribution.addAdapterMailboxConsumer(context.mailbox().consumer(
                    config.getAdapterId(),
                    commandExecutor
            ));
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
                    context.ingress().resultIngress(),
                    context.hostResources().executor()
            ));
        }
        return contribution.build();
    }

    private SocketSessionManager resolveSessionManager(TransportAdapterBootstrapContext context,
                                                       String adapterMailboxKey) {
        return new SocketSessionManager(
                config.getAdapterId(),
                adapterMailboxKey,
                context.sessionEvidence().publisher()
        );
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

