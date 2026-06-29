package com.xa.mass.transport.starter;

import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.runtime.PollingAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.socket.runtime.SocketAdapterRuntimeFactory;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterRuntimeFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bundled embedded adapter starter defaults.
 */
public final class EmbeddedAdapterStarterDefaults {

    public static final String TYPE_POLLING = PollingAdapterRuntimeFactory.TYPE;
    public static final String TYPE_WEBSOCKET = WebSocketAdapterRuntimeFactory.TYPE;
    public static final String TYPE_SOCKET = SocketAdapterRuntimeFactory.TYPE;
    public static final String DEFAULT_POLLING_ADAPTER_ID = PollingAdapterRuntimeFactory.DEFAULT_ADAPTER_ID;

    private EmbeddedAdapterStarterDefaults() {
    }

    public static EmbeddedAdapterStarter createStarter(
            EmbeddedAdapterRuntimeEnvironment environment,
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory) {
        return new EmbeddedAdapterStarter(
                environment,
                createRegistry(pollingPendingDeliveryBufferFactory)
        );
    }

    public static EmbeddedAdapterStarter createStarter(
            EmbeddedAdapterRuntimeEnvironment environment,
            EmbeddedAdapterRuntimeFactoryRegistry registry) {
        return new EmbeddedAdapterStarter(environment, registry);
    }

    public static EmbeddedAdapterRuntimeFactoryRegistry createRegistry(
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory) {
        return new EmbeddedAdapterRuntimeFactoryRegistry(
                defaultFactories(pollingPendingDeliveryBufferFactory)
        );
    }

    public static Map<String, String> webSocketOptions(EmbeddedWebSocketAdapterDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return Map.of(
                WebSocketAdapterRuntimeFactory.OPTION_SERVER_ENABLED, Boolean.toString(declaration.serverEnabled()),
                WebSocketAdapterRuntimeFactory.OPTION_SERVER_PORT, Integer.toString(declaration.serverPort()),
                WebSocketAdapterRuntimeFactory.OPTION_MAX_CONNECTIONS, Integer.toString(declaration.maxConnections()),
                WebSocketAdapterRuntimeFactory.OPTION_ENDPOINT_PATH, declaration.endpointPath()
        );
    }

    public static Map<String, String> socketOptions(EmbeddedSocketAdapterDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return Map.of(
                SocketAdapterRuntimeFactory.OPTION_SERVER_ENABLED, Boolean.toString(declaration.serverEnabled()),
                SocketAdapterRuntimeFactory.OPTION_SERVER_PORT, Integer.toString(declaration.serverPort()),
                SocketAdapterRuntimeFactory.OPTION_MAX_CONNECTIONS, Integer.toString(declaration.maxConnections()),
                SocketAdapterRuntimeFactory.OPTION_BIND_HOST, declaration.bindHost()
        );
    }

    static List<EmbeddedTransportAdapterRuntimeFactory> defaultFactories(
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory) {
        return List.of(
                new PollingAdapterRuntimeFactory(Objects.requireNonNull(
                        pollingPendingDeliveryBufferFactory,
                        "pollingPendingDeliveryBufferFactory"
                )),
                new WebSocketAdapterRuntimeFactory(Map.of()),
                new SocketAdapterRuntimeFactory()
        );
    }
}
