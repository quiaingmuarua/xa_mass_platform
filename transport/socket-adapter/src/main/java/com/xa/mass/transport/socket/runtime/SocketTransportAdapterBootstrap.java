package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import com.xa.mass.transport.socket.session.SocketSessionManager;

import java.util.Objects;

/**
 * Adapter-owned bootstrap for embedded raw-socket runtime contribution.
 */
public final class SocketTransportAdapterBootstrap implements TransportAdapterBootstrap {

    private final String adapterId;
    private final boolean enabled;
    private final boolean serverEnabled;
    private final int serverPort;
    private final int maxConnections;
    private final String bindHost;

    public SocketTransportAdapterBootstrap(SocketAdapterConfig config) {
        SocketAdapterConfig snapshot = new SocketAdapterConfig(Objects.requireNonNull(config, "config"));
        this.adapterId = snapshot.getAdapterId();
        this.enabled = snapshot.isEnabled();
        this.serverEnabled = snapshot.isServerEnabled();
        this.serverPort = snapshot.getServerPort();
        this.maxConnections = snapshot.getMaxConnections();
        this.bindHost = snapshot.getBindHost();
    }

    @Override
    public TransportAdapterDescriptor descriptor() {
        return new TransportAdapterDescriptor(
                adapterId,
                com.xa.mass.transport.WorkerTransportHints.REALTIME
        );
    }

    @Override
    public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
        Objects.requireNonNull(context, "context");
        if (!enabled) {
            return TransportAdapterContribution.empty();
        }

        SocketRuntimeParts parts = createRuntimeParts(context);
        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        contributeAssignedDelivery(contribution, context, parts);
        contributeRawWorkerChannel(contribution, parts);
        contributeServer(contribution, context, parts);
        return contribution.build();
    }

    private SocketRuntimeParts createRuntimeParts(TransportAdapterBootstrapContext context) {
        String adapterMailboxKey = context.mailbox().assignedMailboxKey();
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();
        SocketSessionManager sessionManager = new SocketSessionManager(
                adapterId,
                adapterMailboxKey,
                context.sessionEvidence().publisher()
        );
        return new SocketRuntimeParts(adapterMailboxKey, sessionManager, frameCodec);
    }

    private void contributeAssignedDelivery(TransportAdapterContribution.Builder contribution,
                                            TransportAdapterBootstrapContext context,
                                            SocketRuntimeParts parts) {
        contribution.addTransportBinding(TransportBinding.builder(
                        adapterId,
                        com.xa.mass.transport.WorkerTransportHints.REALTIME
                )
                .adapterMailboxKey(parts.adapterMailboxKey())
                .protocol(SocketAdapterConfig.PROTOCOL)
                .build());
        contribution.addAdapterMailboxConsumer(context.mailbox().consumer(
                adapterId,
                socketCommandExecutor(parts.sessionManager(), parts.frameCodec())
        ));
    }

    private void contributeRawWorkerChannel(TransportAdapterContribution.Builder contribution,
                                            SocketRuntimeParts parts) {
        contribution.addRawWorkerMessageChannel(new SocketRawWorkerMessageChannel(
                adapterId,
                parts.sessionManager()
        ));
    }

    private void contributeServer(TransportAdapterContribution.Builder contribution,
                                  TransportAdapterBootstrapContext context,
                                  SocketRuntimeParts parts) {
        if (!serverEnabled) {
            return;
        }
        contribution.addTransportServer(new SocketTransportServer(
                adapterId,
                bindHost,
                serverPort,
                maxConnections,
                parts.sessionManager(),
                parts.frameCodec(),
                context.ingress().resultIngress(),
                context.hostResources().executor()
        ));
    }

    static AdapterCommandExecutor socketCommandExecutor(SocketSessionManager sessionManager,
                                                        SocketTransportFrameCodec frameCodec) {
        SocketSessionManager requiredSessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        SocketTransportFrameCodec requiredFrameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        return AdapterCommandExecutors.perMessage("Socket", item -> requiredSessionManager.sendToWorker(
                item.selectedWorkerId(),
                requiredFrameCodec.encodeCanonicalTaskDispatch(item)));
    }

    private record SocketRuntimeParts(
            String adapterMailboxKey,
            SocketSessionManager sessionManager,
            SocketTransportFrameCodec frameCodec) {
    }

    private static final class SocketRawWorkerMessageChannel implements RawWorkerMessageChannel {

        private final String adapterId;
        private final SocketSessionManager sessionManager;

        private SocketRawWorkerMessageChannel(
                String adapterId,
                SocketSessionManager sessionManager) {
            this.adapterId = adapterId;
            this.sessionManager = sessionManager;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public boolean sendToWorker(String workerId, String rawJson, String traceId) {
            return sessionManager.sendToWorker(workerId, rawJson);
        }
    }
}

