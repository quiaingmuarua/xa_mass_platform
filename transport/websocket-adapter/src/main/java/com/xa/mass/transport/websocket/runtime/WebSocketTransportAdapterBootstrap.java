package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterInboundResultProcessor;
import com.xa.mass.transport.runtime.embedded.JsonAdapterResultDiagnosticsProvider;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyResultFrameReader;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceRefresher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTransportAdapterBootstrap.class);
    private static final String TYPE_FIELD = "type";

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
        TransportJsonFrameParser frameParser = new TransportJsonFrameParser();
        WorkerChannelActionReplyResultFrameReader resultFrameReader =
                new WorkerChannelActionReplyResultFrameReader(frameParser);
        AdapterInboundResultProcessor<JsonObject> resultProcessor = AdapterInboundResultProcessor.with(
                resultFrameReader,
                context.ingress().resultIngress(),
                new JsonAdapterResultDiagnosticsProvider(config.getAdapterId(), frameParser)::diagnostics
        );
        Consumer<JsonObject> inboundFrameSink =
                frame -> processInboundFrame(frameParser, resultFrameReader, resultProcessor, frame);

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
                frameParser,
                inboundFrameSink,
                sessionRegistry
        );
        if (transportServer != null) {
            contribution.addManagedTransportAdapter(sessionEvidenceRefresher);
            contribution.addTransportServer(transportServer);
        }
        return contribution.build();
    }

    private TransportServer createTransportServer(TransportJsonFrameParser frameParser,
                                                  Consumer<JsonObject> inboundFrameSink,
                                                  WebSocketServerSessionHandle sessionHandle) {
        if (!config.isServerEnabled()) {
            return null;
        }
        TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory =
                config.getTransportServerFactory();
        if (transportServerFactory != null) {
            return transportServerFactory.create(new WebSocketServerFactoryContext(
                    sessionHandle,
                    rawJson -> processInboundRawFrame(frameParser, inboundFrameSink, rawJson),
                    config.getServerPort(),
                    config.getEndpointPath()
            ));
        }
        return new WebSocketServerImpl(
                config.getServerPort(),
                config.getMaxConnections(),
                config.getEndpointPath(),
                frameParser,
                inboundFrameSink,
                sessionHandle
        );
    }

    private static void processInboundRawFrame(TransportJsonFrameParser frameParser,
                                               Consumer<JsonObject> inboundFrameSink,
                                               String rawJson) {
        JsonObject frame = frameParser.parseObject(rawJson);
        if (frame != null) {
            inboundFrameSink.accept(frame);
        }
    }

    private static void processInboundFrame(TransportJsonFrameParser frameParser,
                                            WorkerChannelActionReplyResultFrameReader resultFrameReader,
                                            AdapterInboundResultProcessor<JsonObject> resultProcessor,
                                            JsonObject frame) {
        if (frame == null) {
            return;
        }
        if (isControlFrame(frameParser, frame)) {
            logger.debug("Ignoring WebSocket adapter control frame");
            return;
        }
        if (resultFrameReader.isResultFrame(frame)) {
            resultProcessor.processResult(frame);
            return;
        }
        logger.warn("No canonical task-result handler found for inbound adapter frame");
    }

    private static boolean isControlFrame(TransportJsonFrameParser frameParser, JsonObject frame) {
        String type = frameParser.readString(frame, TYPE_FIELD);
        if (type == null) {
            return false;
        }
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "hello", "handshake", "heartbeat" -> true;
            default -> false;
        };
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

