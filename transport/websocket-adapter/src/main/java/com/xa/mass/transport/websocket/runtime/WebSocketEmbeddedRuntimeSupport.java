package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.websocket.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.server.WebSocketServerImpl;
import com.xa.mass.transport.websocket.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;

import java.util.function.Consumer;

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

    public static WebSocketDispatchRuntimeContext createDispatcherContext(
            MessageTransporter<String, WorkerTransportMessage> messageTransporter,
            WorkerEndpointRegistry endpointRegistry,
            TaskResultIngestChannel taskResultIngestChannel,
            WorkerSystemEventChannel systemEventChannel) {
        return new WebSocketDispatcherContext(
                messageTransporter,
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

    public static WorkerAdapter createRealtimeWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
        return new WebSocketRealtimeWorkerAdapter(taskDispatchChannel);
    }

    public static TransportServer createTransportServer(int port,
                                                        String endpointPath,
                                                        WebSocketDispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry) {
        return createTransportServer(
                port,
                endpointPath,
                dispatcherContext.getFrameCodec(),
                dispatcherContext.getMessageTransporter()::sendInput,
                endpointRegistry
        );
    }

    public static TransportServer createTransportServer(int port,
                                                        String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        Consumer<String> inboundMessageSink,
                                                        WorkerEndpointRegistry endpointRegistry) {
        if (!(endpointRegistry instanceof ServerSessionManager sessionManager)) {
            throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
        }
        return createTransportServer(
                port,
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }

    public static TransportServer createTransportServer(int port,
                                                        String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        Consumer<String> inboundMessageSink,
                                                        ServerSessionManager sessionManager) {
        return new WebSocketServerImpl(
                port,
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }
}
