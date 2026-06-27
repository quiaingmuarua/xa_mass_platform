package com.xa.mass.transport.websocket.runtime;

import com.google.gson.JsonObject;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.transport.runtime.embedded.AdapterDispatchQueueConsumerLoop;
import com.xa.mass.transport.runtime.embedded.AdapterInboundResultProcessor;
import com.xa.mass.transport.runtime.embedded.CompositeEmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.embedded.JsonAdapterResultDiagnosticsProvider;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyResultFrameReader;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceRefresher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Factory for embedded WebSocket adapter runtimes.
 */
public final class WebSocketAdapterRuntimeFactory implements EmbeddedTransportAdapterRuntimeFactory {

    public static final String TYPE = "websocket";
    public static final String OPTION_SERVER_ENABLED = "serverEnabled";
    public static final String OPTION_SERVER_PORT = "serverPort";
    public static final String OPTION_MAX_CONNECTIONS = "maxConnections";
    public static final String OPTION_ENDPOINT_PATH = "endpointPath";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketAdapterRuntimeFactory.class);
    private static final String TYPE_FIELD = "type";

    private final Map<String, TransportServerFactory<WebSocketServerFactoryContext>> serverFactoriesByAdapterId;

    public WebSocketAdapterRuntimeFactory() {
        this(Map.of());
    }

    public WebSocketAdapterRuntimeFactory(
            Map<String, TransportServerFactory<WebSocketServerFactoryContext>> serverFactoriesByAdapterId) {
        this.serverFactoriesByAdapterId = serverFactoriesByAdapterId == null
                ? Map.of()
                : Map.copyOf(serverFactoriesByAdapterId);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public TransportAdapterDescriptor descriptor(EmbeddedAdapterRuntimeSpec spec) {
        return new TransportAdapterDescriptor(spec.adapterId(), WorkerTransportHints.REALTIME);
    }

    @Override
    public EmbeddedTransportAdapterRuntime create(EmbeddedAdapterRuntimeSpec spec,
                                                  EmbeddedAdapterRuntimeEnvironment environment) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        TransportAdapterDescriptor descriptor = descriptor(spec);
        AdapterSessionEvidencePublisher sessionEvidencePublisher = new AdapterSessionEvidencePublisher(
                descriptor.getAdapterId(),
                spec.dispatchQueueKey(),
                environment.endpointLeaseStore(),
                environment.currentSessionConnectSink(),
                environment.currentSessionDisconnectSink()
        );
        WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        WebSocketSessionEvidenceRefresher sessionEvidenceRefresher =
                new WebSocketSessionEvidenceRefresher(descriptor.getAdapterId(), sessionRegistry, sessionEvidencePublisher);
        TransportJsonFrameParser frameParser = new TransportJsonFrameParser();
        WorkerChannelActionReplyResultFrameReader resultFrameReader =
                new WorkerChannelActionReplyResultFrameReader(frameParser);
        AdapterResultIngressSink resultSink = entry ->
                environment.resultQueue().offer(spec.resultQueueKey(), entry);
        AdapterInboundResultProcessor<JsonObject> resultProcessor = AdapterInboundResultProcessor.with(
                resultFrameReader,
                resultSink,
                new JsonAdapterResultDiagnosticsProvider(descriptor.getAdapterId(), frameParser)::diagnostics
        );
        Consumer<JsonObject> inboundFrameSink =
                frame -> processInboundFrame(frameParser, resultFrameReader, resultProcessor, frame);
        AdapterCommandExecutor commandExecutor =
                webSocketCommandExecutor(sessionRegistry, new WorkerChannelFrameJsonCodec());
        AdapterDispatchQueueConsumerLoop dispatchConsumer = new AdapterDispatchQueueConsumerLoop(
                spec.dispatchQueueKey(),
                environment.dispatchQueue(),
                commandExecutor,
                environment.deliveryFailureHandler(),
                environment.executor()
        );
        TransportServer server = createTransportServer(spec, frameParser, inboundFrameSink, sessionRegistry);
        TransportBinding binding = TransportBinding.builder(descriptor.getAdapterId(), descriptor.getTransportHint())
                .adapterMailboxKey(spec.dispatchQueueKey())
                .protocol(WebSocketAdapterConfig.PROTOCOL)
                .build();
        List<ManagedTransportAdapter> managedAdapters = server == null
                ? List.of(dispatchConsumer)
                : List.of(dispatchConsumer, sessionEvidenceRefresher);
        List<TransportServer> servers = server == null ? List.of() : List.of(server);
        return new CompositeEmbeddedTransportAdapterRuntime(descriptor, binding, managedAdapters, servers);
    }

    public static Map<String, String> options(WebSocketAdapterConfig config) {
        WebSocketAdapterConfig snapshot = new WebSocketAdapterConfig(Objects.requireNonNull(config, "config"));
        return Map.of(
                OPTION_SERVER_ENABLED, Boolean.toString(snapshot.isServerEnabled()),
                OPTION_SERVER_PORT, Integer.toString(snapshot.getServerPort()),
                OPTION_MAX_CONNECTIONS, Integer.toString(snapshot.getMaxConnections()),
                OPTION_ENDPOINT_PATH, snapshot.getEndpointPath()
        );
    }

    static AdapterCommandExecutor webSocketCommandExecutor(WebSocketSessionRegistry sessionRegistry,
                                                           WorkerChannelFrameJsonCodec frameCodec) {
        WebSocketSessionRegistry requiredRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        WorkerChannelFrameJsonCodec requiredFrameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        return AdapterCommandExecutors.perMessage("WebSocket", item -> requiredRegistry.sendTextToWorker(
                item.selectedWorkerId(),
                requiredFrameCodec.encodeAction(item.payload())));
    }

    private TransportServer createTransportServer(EmbeddedAdapterRuntimeSpec spec,
                                                  TransportJsonFrameParser frameParser,
                                                  Consumer<JsonObject> inboundFrameSink,
                                                  WebSocketServerSessionHandle sessionHandle) {
        if (!booleanOption(spec, OPTION_SERVER_ENABLED, true)) {
            return null;
        }
        int serverPort = intOption(spec, OPTION_SERVER_PORT, 8080);
        int maxConnections = intOption(spec, OPTION_MAX_CONNECTIONS, 1000);
        String endpointPath = textOption(spec, OPTION_ENDPOINT_PATH, "/ws");
        TransportServerFactory<WebSocketServerFactoryContext> serverFactory =
                serverFactoriesByAdapterId.get(spec.adapterId());
        if (serverFactory != null) {
            return serverFactory.create(new WebSocketServerFactoryContext(
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
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "hello", "handshake", "heartbeat" -> true;
            default -> false;
        };
    }

    private static boolean booleanOption(EmbeddedAdapterRuntimeSpec spec, String key, boolean fallback) {
        String value = spec.option(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private static int intOption(EmbeddedAdapterRuntimeSpec spec, String key, int fallback) {
        String value = spec.option(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static String textOption(EmbeddedAdapterRuntimeSpec spec, String key, String fallback) {
        String value = spec.option(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
