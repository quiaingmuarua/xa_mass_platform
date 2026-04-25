package com.xa.mass.gateway.runtime;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.gateway.server.WebSocketServerImpl;
import com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.function.Consumer;

/**
 * WebSocket adapter bootstrap support for the embedded gateway runtime.
 *
 * <p>This helper keeps adapter-specific endpoint-registry and system-event
 * defaults inside the gateway module instead of teaching SDK config about
 * concrete session-manager types.
 */
public final class WebSocketGatewayRuntimeSupport {

    private WebSocketGatewayRuntimeSupport() {
    }

    public static ServerSessionManager createEndpointRegistry() {
        return new ServerSessionManager();
    }

    public static WebSocketTransportFrameCodec resolveFrameCodec(WebSocketTransportFrameCodec configuredCodec) {
        return configuredCodec != null ? configuredCodec : new WebSocketTransportFrameCodec();
    }

    public static DispatchRuntimeContext createDispatcherContext(
            MessageTransporter<String, OutboundDelivery> messageTransporter,
            WorkerEndpointRegistry endpointRegistry,
            WebSocketTransportFrameCodec configuredCodec,
            TaskResultIngestChannel taskResultIngestChannel,
            WorkerSystemEventChannel systemEventChannel) {
        return new DispatcherContext(
                messageTransporter,
                endpointRegistry,
                resolveFrameCodec(configuredCodec),
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

    public static TransportServer createTransportServer(String endpointPath,
                                                        DispatchRuntimeContext dispatcherContext,
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
            throw new IllegalStateException("WebSocket transport requires gateway-managed WebSocket endpoint registry");
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
