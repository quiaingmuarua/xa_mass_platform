package com.xa.mass.transport.starter;

import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.runtime.PollingAdapterRuntimeFactory;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.socket.runtime.SocketAdapterRuntimeFactory;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterRuntimeFactory;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;

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
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory,
            Map<String, TransportServerFactory<WebSocketServerFactoryContext>> webSocketServerFactoriesByAdapterId) {
        return new EmbeddedAdapterStarter(
                environment,
                createRegistry(pollingPendingDeliveryBufferFactory, webSocketServerFactoriesByAdapterId)
        );
    }

    public static EmbeddedAdapterStarter createStarter(
            EmbeddedAdapterRuntimeEnvironment environment,
            EmbeddedAdapterRuntimeFactoryRegistry registry) {
        return new EmbeddedAdapterStarter(environment, registry);
    }

    public static EmbeddedAdapterRuntimeFactoryRegistry createRegistry(
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory,
            Map<String, TransportServerFactory<WebSocketServerFactoryContext>> webSocketServerFactoriesByAdapterId) {
        return new EmbeddedAdapterRuntimeFactoryRegistry(
                defaultFactories(pollingPendingDeliveryBufferFactory, webSocketServerFactoriesByAdapterId)
        );
    }

    public static Map<String, String> webSocketOptions(WebSocketAdapterConfig config) {
        return WebSocketAdapterRuntimeFactory.options(config);
    }

    public static Map<String, String> socketOptions(SocketAdapterConfig config) {
        return SocketAdapterRuntimeFactory.options(config);
    }

    static List<EmbeddedTransportAdapterRuntimeFactory> defaultFactories(
            Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory,
            Map<String, TransportServerFactory<WebSocketServerFactoryContext>> webSocketServerFactoriesByAdapterId) {
        return List.of(
                new PollingAdapterRuntimeFactory(Objects.requireNonNull(
                        pollingPendingDeliveryBufferFactory,
                        "pollingPendingDeliveryBufferFactory"
                )),
                new WebSocketAdapterRuntimeFactory(webSocketServerFactoriesByAdapterId == null
                        ? Map.of()
                        : Map.copyOf(webSocketServerFactoriesByAdapterId)),
                new SocketAdapterRuntimeFactory()
        );
    }
}
