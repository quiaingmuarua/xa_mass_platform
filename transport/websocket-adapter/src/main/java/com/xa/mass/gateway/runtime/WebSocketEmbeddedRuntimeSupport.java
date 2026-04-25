package com.xa.mass.gateway.runtime;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.WebSocketDispatcherContext;
import com.xa.mass.gateway.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.gateway.server.WebSocketServerImpl;
import com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.worker.WebSocketRealtimeWorkerAdapter;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

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
            MessageTransporter<String, OutboundDelivery> messageTransporter,
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

    public static TransportServer createTransportServer(String endpointPath,
                                                        WebSocketDispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry) {
        return createTransportServer(
                endpointPath,
                dispatcherContext.getFrameCodec(),
                dispatcherContext.getMessageTransporter()::sendInput,
                endpointRegistry
        );
    }

    public static TransportServer createTransportServer(String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        Consumer<String> inboundMessageSink,
                                                        WorkerEndpointRegistry endpointRegistry) {
        if (!(endpointRegistry instanceof ServerSessionManager sessionManager)) {
            throw new IllegalStateException("WebSocket transport requires a WebSocket-managed endpoint registry");
        }
        return createTransportServer(
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }

    public static TransportServer createTransportServer(String endpointPath,
                                                        WebSocketTransportFrameCodec frameCodec,
                                                        Consumer<String> inboundMessageSink,
                                                        ServerSessionManager sessionManager) {
        return new WebSocketServerImpl(
                endpointPath,
                frameCodec,
                inboundMessageSink,
                sessionManager
        );
    }
}
