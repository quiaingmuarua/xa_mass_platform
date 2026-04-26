package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.socket.dispatcher.SocketTaskDispatchChannel;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import com.xa.mass.transport.socket.worker.SocketRealtimeWorkerAdapter;

/**
 * Adapter-owned bootstrap for embedded raw-socket runtime contribution.
 */
public final class SocketTransportAdapterBootstrap implements TransportAdapterBootstrap<WorkerTransportMessage> {

    private final SocketAdapterConfig config;

    public SocketTransportAdapterBootstrap(SocketAdapterConfig config) {
        this.config = new SocketAdapterConfig(config);
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return new TransportAdapterDescriptor(
                SocketRealtimeWorkerAdapter.PROTOCOL,
                com.xa.mass.transport.WorkerTransportHints.REALTIME,
                java.util.Set.of("tcp-socket")
        );
    }

    @Override
    public TransportAdapterContribution create(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
        SocketSessionManager sessionManager = resolveSessionManager(context);
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            contribution.transportBinding(TransportBinding.builder(
                    new SocketRealtimeWorkerAdapter(new SocketTaskDispatchChannel(
                            sessionManager,
                            frameCodec,
                            context.getDeliveryService()
                    ))
            ).build());
            contribution.rawWorkerMessageChannel(new SocketRawWorkerMessageChannel(sessionManager));
        }
        if (config.isServerEnabled()) {
            contribution.transportServer(new SocketTransportServer(
                    config.getBindHost(),
                    config.getServerPort(),
                    config.getMaxConnections(),
                    sessionManager,
                    frameCodec,
                    context.getTaskResultIngestChannel(),
                    context.getSystemEventChannel()
            ));
        }
        return contribution.build();
    }

    private SocketSessionManager resolveSessionManager(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
        if (context.getEndpointRegistry() instanceof SocketSessionManager sessionManager) {
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            SocketSessionManager sessionManager = composite.getOrRegister(
                    SocketRealtimeWorkerAdapter.PROTOCOL,
                    () -> new SocketSessionManager(context.getSystemEventChannel())
            );
            sessionManager.setSystemEventChannel(context.getSystemEventChannel());
            return sessionManager;
        }
        throw new IllegalStateException("Socket transport requires a socket-managed endpoint registry");
    }

    private static final class SocketRawWorkerMessageChannel implements RawWorkerMessageChannel {

        private final SocketSessionManager sessionManager;

        private SocketRawWorkerMessageChannel(SocketSessionManager sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public String adapterId() {
            return SocketRealtimeWorkerAdapter.PROTOCOL;
        }

        @Override
        public boolean supports(String workerId, String workerAdapterId) {
            return adapterId().equalsIgnoreCase(workerAdapterId == null ? "" : workerAdapterId.trim())
                    && workerId != null
                    && !workerId.isBlank()
                    && sessionManager.isWorkerOnline(workerId);
        }

        @Override
        public void send(String workerId, String rawJson, String traceId) {
            sessionManager.sendMessage(workerId, rawJson);
        }
    }
}
