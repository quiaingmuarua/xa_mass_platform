package com.xa.mass.engine.worker;

import com.xa.mass.engine.listener.TaskMsgDispatchListener;

import java.util.Set;

/**
 * Extension seam for worker transport adapters (WebSocket, HTTP, gRPC, etc.).
 *
 * <p>A {@code WorkerAdapter} bundles the dispatch side of the worker lifecycle
 * (pushing task messages to workers) with a protocol identifier so that multiple
 * transports can coexist and be selected at runtime.
 *
 * <p>The result callback side is transport-specific and wired externally
 * (e.g., as a {@code MassMessageHandler} in the gateway layer).
 */
public interface WorkerAdapter extends TaskMsgDispatchListener {

    /**
     * Returns the transport protocol name for this adapter.
     * Examples: {@code "websocket"}, {@code "http"}, {@code "grpc"}.
     */
    String protocol();

    /**
     * Returns additional strategy aliases that should resolve to this adapter.
     *
     * <p>Aliases should describe transport capability or delivery style, for
     * example {@code "realtime"}, {@code "ws"}, {@code "pull"}, or
     * {@code "queue"}.
     */
    default Set<String> aliases() {
        return Set.of();
    }
}
