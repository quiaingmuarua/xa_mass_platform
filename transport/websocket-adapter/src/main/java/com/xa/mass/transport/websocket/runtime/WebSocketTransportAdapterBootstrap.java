package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.transport.runtime.embedded.AdapterInboundResultProcessor;
import com.xa.mass.transport.runtime.embedded.JsonAdapterResultDiagnosticsProvider;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyResultFrameReader;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceRefresher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter-owned bootstrap for embedded WebSocket runtime contribution.
 */
public final class WebSocketTransportAdapterBootstrap implements TransportAdapterBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTransportAdapterBootstrap.class);
    private static final String TYPE_FIELD = "type";

    private final String adapterId;
    private final boolean enabled;
    private final boolean serverEnabled;
    private final int serverPort;
    private final int maxConnections;
    private final String endpointPath;
    private final TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory;

    public WebSocketTransportAdapterBootstrap(WebSocketAdapterConfig config) {
        this(config, null);
    }

    public WebSocketTransportAdapterBootstrap(
            WebSocketAdapterConfig config,
            TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory) {
        WebSocketAdapterConfig snapshot = new WebSocketAdapterConfig(Objects.requireNonNull(config, "config"));
        this.adapterId = snapshot.getAdapterId();
        this.enabled = snapshot.isEnabled();
        this.serverEnabled = snapshot.isServerEnabled();
        this.serverPort = snapshot.getServerPort();
        this.maxConnections = snapshot.getMaxConnections();
        this.endpointPath = snapshot.getEndpointPath();
        this.transportServerFactory = transportServerFactory;
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

        WebSocketRuntimeParts parts = createRuntimeParts(context);
        TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();

        contributeAssignedDelivery(contribution, context, parts);
        contributeRawWorkerChannel(contribution, parts);
        contributeServer(contribution, parts);
        return contribution.build();
    }

    private WebSocketRuntimeParts createRuntimeParts(TransportAdapterBootstrapContext context) {
        String adapterMailboxKey = context.mailbox().assignedMailboxKey();
        AdapterSessionEvidencePublisher sessionEvidencePublisher = context.sessionEvidence().publisher();
        WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        WebSocketSessionEvidenceRefresher sessionEvidenceRefresher =
                new WebSocketSessionEvidenceRefresher(adapterId, sessionRegistry, sessionEvidencePublisher);
        TransportJsonFrameParser frameParser = new TransportJsonFrameParser();
        WorkerChannelActionReplyResultFrameReader resultFrameReader =
                new WorkerChannelActionReplyResultFrameReader(frameParser);
        AdapterInboundResultProcessor<JsonObject> resultProcessor = AdapterInboundResultProcessor.with(
                resultFrameReader,
                context.ingress().resultIngress(),
                new JsonAdapterResultDiagnosticsProvider(adapterId, frameParser)::diagnostics
        );
        Consumer<JsonObject> inboundFrameSink =
                frame -> processInboundFrame(frameParser, resultFrameReader, resultProcessor, frame);

        return new WebSocketRuntimeParts(
                adapterMailboxKey,
                sessionRegistry,
                sessionEvidenceRefresher,
                frameParser,
                inboundFrameSink
        );
    }

    private void contributeAssignedDelivery(TransportAdapterContribution.Builder contribution,
                                            TransportAdapterBootstrapContext context,
                                            WebSocketRuntimeParts parts) {
        contribution.addTransportBinding(TransportBinding.builder(
                        adapterId,
                        com.xa.mass.transport.WorkerTransportHints.REALTIME
                )
                .adapterMailboxKey(parts.adapterMailboxKey())
                .protocol(WebSocketAdapterConfig.PROTOCOL)
                .build());
        contribution.addAdapterMailboxConsumer(context.mailbox().consumer(
                adapterId,
                webSocketCommandExecutor(parts.sessionRegistry(), new WorkerChannelFrameJsonCodec())
        ));
    }

    private void contributeRawWorkerChannel(TransportAdapterContribution.Builder contribution,
                                            WebSocketRuntimeParts parts) {
        contribution.addRawWorkerMessageChannel(new WebSocketRawWorkerMessageChannel(
                adapterId,
                parts.sessionRegistry()
        ));
    }

    private void contributeServer(TransportAdapterContribution.Builder contribution,
                                  WebSocketRuntimeParts parts) {
        TransportServer transportServer = createTransportServer(
                parts.frameParser(),
                parts.inboundFrameSink(),
                parts.sessionRegistry()
        );
        if (transportServer != null) {
            contribution.addManagedTransportAdapter(parts.sessionEvidenceRefresher());
            contribution.addTransportServer(transportServer);
        }
    }

    static AdapterCommandExecutor webSocketCommandExecutor(WebSocketSessionRegistry sessionRegistry,
                                                           WorkerChannelFrameJsonCodec frameCodec) {
        WebSocketSessionRegistry requiredRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        WorkerChannelFrameJsonCodec requiredFrameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        return AdapterCommandExecutors.perMessage("WebSocket", item -> requiredRegistry.sendTextToWorker(
                item.selectedWorkerId(),
                requiredFrameCodec.encodeAction(item.payload())));
    }

    private TransportServer createTransportServer(TransportJsonFrameParser frameParser,
                                                  Consumer<JsonObject> inboundFrameSink,
                                                  WebSocketServerSessionHandle sessionHandle) {
        if (!serverEnabled) {
            return null;
        }
        if (transportServerFactory != null) {
            return transportServerFactory.create(new WebSocketServerFactoryContext(
                    sessionHandle,
                    rawJson -> processInboundRawFrame(frameParser, inboundFrameSink, rawJson),
                    serverPort,
                    endpointPath
            ));
        }
        return new WebSocketServerImpl(
                serverPort,
                maxConnections,
                endpointPath,
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

    private record WebSocketRuntimeParts(
            String adapterMailboxKey,
            WebSocketSessionRegistry sessionRegistry,
            WebSocketSessionEvidenceRefresher sessionEvidenceRefresher,
            TransportJsonFrameParser frameParser,
            Consumer<JsonObject> inboundFrameSink) {
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

