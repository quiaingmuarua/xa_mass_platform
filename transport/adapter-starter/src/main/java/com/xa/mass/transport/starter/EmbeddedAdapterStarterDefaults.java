package com.xa.mass.transport.starter;

import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerTransportHints;
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
import java.util.Locale;
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
                defaultFactories(pollingPendingDeliveryBufferFactory, webSocketServerFactoriesByAdapterId)
        );
    }

    public static Map<String, String> webSocketOptions(WebSocketAdapterConfig config) {
        return WebSocketAdapterRuntimeFactory.options(config);
    }

    public static Map<String, String> socketOptions(SocketAdapterConfig config) {
        return SocketAdapterRuntimeFactory.options(config);
    }

    public static String transportHintForType(String type) {
        String normalized = normalizeType(type);
        return switch (normalized) {
            case TYPE_POLLING -> WorkerTransportHints.POLLING;
            case TYPE_WEBSOCKET, TYPE_SOCKET -> WorkerTransportHints.REALTIME;
            default -> throw new IllegalArgumentException("Unsupported embedded adapter type: " + type);
        };
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

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("adapter runtime type must not be blank");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
