package com.xa.mass.transport.socket.runtime;

import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.AdapterHostExecutor;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.transport.runtime.embedded.AdapterDispatchQueueConsumerLoop;
import com.xa.mass.transport.runtime.embedded.CompositeEmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import com.xa.mass.transport.socket.session.SocketSessionManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for embedded raw-socket adapter runtimes.
 */
public final class SocketAdapterRuntimeFactory implements EmbeddedTransportAdapterRuntimeFactory {

    public static final String TYPE = "socket";
    public static final String OPTION_SERVER_ENABLED = "serverEnabled";
    public static final String OPTION_SERVER_PORT = "serverPort";
    public static final String OPTION_MAX_CONNECTIONS = "maxConnections";
    public static final String OPTION_BIND_HOST = "bindHost";

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
                environment.endpointLeaseStore(),
                environment.currentSessionDisconnectSink()
        );
        SocketTransportFrameCodec frameCodec = new SocketTransportFrameCodec();
        SocketSessionManager sessionManager = new SocketSessionManager(
                descriptor.getAdapterId(),
                spec.dispatchQueueKey(),
                sessionEvidencePublisher
        );
        AdapterCommandExecutor commandExecutor = socketCommandExecutor(sessionManager, frameCodec);
        AdapterDispatchQueueConsumerLoop dispatchConsumer = new AdapterDispatchQueueConsumerLoop(
                spec.dispatchQueueKey(),
                environment.dispatchQueue(),
                commandExecutor,
                environment.executor()
        );
        TransportServer server = createServer(spec, descriptor, sessionManager, frameCodec, environment);
        TransportBinding binding = TransportBinding.builder(descriptor.getAdapterId(), descriptor.getTransportHint())
                .adapterMailboxKey(spec.dispatchQueueKey())
                .protocol(SocketAdapterConfig.PROTOCOL)
                .build();
        List<ManagedTransportAdapter> managedAdapters = List.of(dispatchConsumer);
        List<TransportServer> servers = server == null ? List.of() : List.of(server);
        return new CompositeEmbeddedTransportAdapterRuntime(descriptor, binding, managedAdapters, servers);
    }

    public static Map<String, String> options(SocketAdapterConfig config) {
        SocketAdapterConfig snapshot = new SocketAdapterConfig(Objects.requireNonNull(config, "config"));
        return Map.of(
                OPTION_SERVER_ENABLED, Boolean.toString(snapshot.isServerEnabled()),
                OPTION_SERVER_PORT, Integer.toString(snapshot.getServerPort()),
                OPTION_MAX_CONNECTIONS, Integer.toString(snapshot.getMaxConnections()),
                OPTION_BIND_HOST, snapshot.getBindHost()
        );
    }

    static AdapterCommandExecutor socketCommandExecutor(SocketSessionManager sessionManager,
                                                        SocketTransportFrameCodec frameCodec) {
        SocketSessionManager requiredSessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        SocketTransportFrameCodec requiredFrameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        return AdapterCommandExecutors.perMessage("Socket", item -> requiredSessionManager.sendToWorker(
                item.selectedWorkerId(),
                requiredFrameCodec.encodeCanonicalTaskDispatch(item)));
    }

    private TransportServer createServer(EmbeddedAdapterRuntimeSpec spec,
                                         TransportAdapterDescriptor descriptor,
                                         SocketSessionManager sessionManager,
                                         SocketTransportFrameCodec frameCodec,
                                         EmbeddedAdapterRuntimeEnvironment environment) {
        if (!booleanOption(spec, OPTION_SERVER_ENABLED, false)) {
            return null;
        }
        AdapterResultIngressSink resultSink = entry ->
                environment.resultQueue().offer(spec.resultQueueKey(), entry);
        AdapterHostExecutor hostExecutor = environment.executor()::submit;
        return new SocketTransportServer(
                descriptor.getAdapterId(),
                textOption(spec, OPTION_BIND_HOST, "0.0.0.0"),
                intOption(spec, OPTION_SERVER_PORT, 18089),
                intOption(spec, OPTION_MAX_CONNECTIONS, 1000),
                sessionManager,
                frameCodec,
                resultSink,
                hostExecutor
        );
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
