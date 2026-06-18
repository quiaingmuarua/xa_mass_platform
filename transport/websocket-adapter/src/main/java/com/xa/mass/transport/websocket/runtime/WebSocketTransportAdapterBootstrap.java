package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
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
import com.xa.mass.transport.websocket.session.WebSocketRawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.websocket.session.WebSocketSelectedWorkerRegistry;
import com.xa.mass.transport.websocket.session.WebSocketSelectedWorkerSender;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.session.WebSocketSessionController;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceDriver;
import com.xa.mass.transport.websocket.session.WebSocketSessionRefreshLoop;
import com.xa.mass.transport.websocket.session.WebSocketSessionStore;

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
        WebSocketSessionStore sessionStore = new WebSocketSessionStore(config.getAdapterId());
        WebSocketSessionEvidenceDriver evidenceDriver = new WebSocketSessionEvidenceDriver(config.getAdapterId());
        evidenceDriver.setEndpointLeaseStore(context.getEndpointLeaseStore());
        evidenceDriver.setDeliveryCommandConsumerRegistry(context.getDeliveryCommandConsumerRegistry());
        evidenceDriver.setWorkerPresenceIngress(context.getWorkerPresenceIngress());
        WebSocketSessionRefreshLoop refreshLoop =
                new WebSocketSessionRefreshLoop(config.getAdapterId(), sessionStore, evidenceDriver);
        WebSocketSessionController sessionController = new WebSocketSessionController(
                sessionStore,
                evidenceDriver,
                refreshLoop
        );
        WebSocketSelectedWorkerSender selectedWorkerSender = new WebSocketSelectedWorkerSender(sessionStore);
        WebSocketSelectedWorkerRegistry selectedWorkerRegistry = new WebSocketSelectedWorkerRegistry(
                config.getAdapterId(),
                sessionStore,
                selectedWorkerSender,
                sessionController
        );
        WebSocketRawWorkerRouteEndpointRegistry rawRouteEndpointRegistry =
                new WebSocketRawWorkerRouteEndpointRegistry(config.getAdapterId(), sessionStore);
        WebSocketJsonFrameParser frameParser = new WebSocketJsonFrameParser();
        WebSocketResultIngressFrameReader resultFrameReader =
                new WebSocketResultIngressFrameReader(config.getAdapterId(), frameParser);
        WebSocketSessionOpenFrameReader sessionOpenFrameReader =
                new WebSocketSessionOpenFrameReader(frameParser);
        WebSocketCommandDispatchContext commandContext = new WebSocketCommandDispatchContext(
                config.getAdapterId(),
                selectedWorkerRegistry
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
            registerSelectedWorkerRegistry(context, selectedWorkerRegistry);
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
            contribution.addEndpointInspector(new WebSocketEndpointInspector(sessionStore));
        }

        TransportServer transportServer = createTransportServer(
                dispatcherContext,
                sessionOpenFrameReader,
                sessionController
        );
        if (transportServer != null) {
            contribution.addTransportServer(transportServer);
        }
        return contribution.build();
    }

    private void registerSelectedWorkerRegistry(TransportAdapterBootstrapContext context,
                                                WebSocketSelectedWorkerRegistry selectedWorkerRegistry) {
        if (context.getEndpointRegistry() instanceof CompositeWorkerEndpointRegistry composite) {
            composite.register(config.getAdapterId(), selectedWorkerRegistry);
            return;
        }
        throw new IllegalStateException("WebSocket transport requires a composite selected-worker endpoint registry");
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

