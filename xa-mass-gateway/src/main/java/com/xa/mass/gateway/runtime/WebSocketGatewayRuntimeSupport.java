package com.xa.mass.gateway.runtime;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.server.WebSocketServerImpl;
import com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

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

    public static WorkerEndpointRegistry createEndpointRegistry() {
        return new ServerSessionManager();
    }

    public static WorkerSystemEventChannel resolveSystemEventChannel(WorkerEndpointRegistry endpointRegistry) {
        if (endpointRegistry instanceof ServerSessionManager sessionManager) {
            return sessionManager.getSystemEventChannel();
        }
        return new EventBusWorkerSystemEventChannel();
    }

    public static ServerSessionManager requireSessionManager(WorkerEndpointRegistry endpointRegistry) {
        if (endpointRegistry instanceof ServerSessionManager sessionManager) {
            return sessionManager;
        }
        throw new IllegalStateException("WebSocket transport requires gateway-managed WebSocket endpoint registry");
    }

    public static TransportServer createTransportServer(String endpointPath,
                                                        DispatchRuntimeContext dispatcherContext,
                                                        WorkerEndpointRegistry endpointRegistry) {
        return new WebSocketServerImpl(
                endpointPath,
                dispatcherContext,
                requireSessionManager(endpointRegistry)
        );
    }
}
