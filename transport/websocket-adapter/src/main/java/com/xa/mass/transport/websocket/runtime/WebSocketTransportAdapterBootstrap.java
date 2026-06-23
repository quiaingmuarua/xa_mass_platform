package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketResultIngressFrameReader;
import com.xa.mass.transport.websocket.frame.WebSocketSessionOpenFrameReader;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceRefresher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;

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
        String adapterMailboxKey = context.mailbox().assignedMailboxKey();
        AdapterSessionEvidencePublisher sessionEvidencePublisher = context.sessionEvidence().publisher();
        WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        WebSocketSessionEvidenceRefresher sessionEvidenceRefresher =
                new WebSocketSessionEvidenceRefresher(config.getAdapterId(), sessionRegistry, sessionEvidencePublisher);
        WebSocketJsonFrameParser frameParser = new WebSocketJsonFrameParser();
        WebSocketResultIngressFrameReader resultFrameReader =
                new WebSocketResultIngressFrameReader(config.getAdapterId(), frameParser);
        WebSocketSessionOpenFrameReader sessionOpenFrameReader =
                new WebSocketSessionOpenFrameReader(frameParser);
        WebSocketDispatcherContext dispatcherContext = new WebSocketDispatcherContext(
                config.getAdapterId(),
                frameParser,
                resultFrameReader,
                context.ingress().resultIngress()
        );

        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();
        if (config.isEnabled()) {
            WebSocketTaskDispatchChannel commandExecutor =
                    new WebSocketTaskDispatchChannel(sessionRegistry);
            contribution.addTransportBinding(TransportBinding.builder(
                            config.getAdapterId(),
                            com.xa.mass.transport.WorkerTransportHints.REALTIME
                    )
                    .adapterMailboxKey(adapterMailboxKey)
                    .protocol(WebSocketAdapterConfig.PROTOCOL)
                    .build());
            contribution.addAdapterMailboxConsumer(context.mailbox().consumer(
                    config.getAdapterId(),
                    commandExecutor
            ));
            contribution.addRawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(
                    config.getAdapterId(),
                    sessionRegistry
            ));
        }

        TransportServer transportServer = createTransportServer(
                dispatcherContext,
                sessionOpenFrameReader,
                sessionRegistry
        );
        if (transportServer != null) {
            contribution.addManagedTransportAdapter(sessionEvidenceRefresher);
            contribution.addTransportServer(transportServer);
        }
        return contribution.build();
    }

    private TransportServer createTransportServer(WebSocketDispatcherContext dispatcherContext,
                                                  WebSocketSessionOpenFrameReader sessionOpenFrameReader,
                                                  WebSocketServerSessionHandle sessionHandle) {
        if (!config.isServerEnabled()) {
            return null;
        }
        TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory =
                config.getTransportServerFactory();
        if (transportServerFactory != null) {
            return transportServerFactory.create(new WebSocketServerFactoryContext(
                    sessionHandle,
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
                sessionHandle
        );
    }

    private static final class WebSocketRawWorkerMessageChannel
            implements com.xa.mass.transport.runtime.RawWorkerMessageChannel {

        private final String adapterId;
        private final WebSocketSessionRegistry sessionRegistry;

        private WebSocketRawWorkerMessageChannel(
                String adapterId,
                WebSocketSessionRegistry sessionRegistry) {
            this.adapterId = adapterId;
            this.sessionRegistry = sessionRegistry;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public boolean sendToWorker(String workerId, String rawJson, String traceId) {
            return sessionRegistry.sendTextToWorker(workerId, rawJson);
        }
    }
}

