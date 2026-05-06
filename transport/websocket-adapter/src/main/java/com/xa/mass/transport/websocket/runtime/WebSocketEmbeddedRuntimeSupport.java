package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessageSink;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInputProcessor;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;


/**
 * WebSocket-adapter-owned defaults for embedded runtime assembly.
 *
 * <p>The current transport-server and realtime worker adapter defaults remain
 * WebSocket-backed, but that ownership stays inside the adapter module instead
 * of leaking WebSocket-specific classes into SDK runtime assembly.
 */
public final class WebSocketEmbeddedRuntimeSupport {

    private WebSocketEmbeddedRuntimeSupport() {
    }

    public static ServerSessionManager createEndpointRegistry() {
        return new ServerSessionManager();
    }

    public static ServerSessionManager createEndpointRegistry(String adapterId) {
        return new ServerSessionManager(adapterId);
    }

    public static WebSocketDispatchRuntimeContext createDispatcherContext(
            String adapterId,
            WorkerEndpointRegistry endpointRegistry,
            TaskResultIngestChannel taskResultIngestChannel,
            WorkerSystemEventChannel systemEventChannel) {
        return new WebSocketDispatcherContext(
                adapterId,
                endpointRegistry,
                new WebSocketTransportFrameCodec(),
                taskResultIngestChannel,
                systemEventChannel
        );
    }

    public static WorkerSystemEventChannel resolveSystemEventChannel(WorkerEndpointRegistry endpointRegistry) {
        if (endpointRegistry instanceof ServerSessionManager sessionManager) {
            return sessionManager.getSystemEventChannel();
        }
        return new EventBusWorkerSystemEventChannel();
    }

    public static WorkerAdapter createRealtimeWorkerAdapter(String adapterId, TaskDispatchChannel taskDispatchChannel) {
        return new WebSocketRealtimeWorkerAdapter(adapterId, taskDispatchChannel);
    }

    public static TransportServer createTransportServer(int port,
                                                        int maxConnections,
                                                        String endpointPath,
                                                        WebSocketDispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry) {
        WebSocketInputProcessor inputProcessor = new WebSocketInputProcessor(dispatcherContext);
        return createTransportServer(
                port,
                maxConnections,
                endpointPath,
                dispatcherContext.getFrameCodec(),
                inputProcessor::process,
                endpointRegistry
        );
    }

    public static TransportServer createTransportServer(WebSocketAdapterConfig config,
                                                        WebSocketDispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry) {
        return createTransportServer(
                config,
                dispatcherContext,
                endpointRegistry,
                config.getServerPort()
        );
    }

    public static TransportServer createTransportServer(WebSocketAdapterConfig config,
                                                        WebSocketDispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry,
                                                        int port) {
        if (!config.isServerEnabled()) {
            return null;
        }
        TransportServerFactory<TransportServerFactoryContext> transportServerFactory =
                config.getTransportServerFactory();
        if (transportServerFactory == null) {
            return createTransportServer(
                    port,
                    config.getMaxConnections(),
                    config.getEndpointPath(),
                    dispatcherContext,
                    endpointRegistry
            );
        }
        return transportServerFactory.create(new TransportServerFactoryContext(
                endpointRegistry,
                new WebSocketInputProcessor(dispatcherContext)::process,
                port,
                config.getEndpointPath()
        ));
    }

    public static TransportServer createTransportServer(int port,
                                                        int maxConnections,
                                                        String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        WebSocketInboundMessageSink inboundMessageSink,
                                                        WorkerEndpointRegistry endpointRegistry) {
        if (!(endpointRegistry instanceof ServerSessionManager sessionManager)) {
            throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
        }
        return createTransportServer(
                port,
                maxConnections,
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }

    public static TransportServer createTransportServer(int port,
                                                        int maxConnections,
                                                        String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        WebSocketInboundMessageSink inboundMessageSink,
                                                        ServerSessionManager sessionManager) {
        return new WebSocketServerImpl(
                port,
                maxConnections,
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }
}
