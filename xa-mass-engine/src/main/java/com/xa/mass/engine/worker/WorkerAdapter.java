package com.xa.mass.engine.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;

import java.util.Set;

/**
 * Extension seam for worker transport adapters (WebSocket, HTTP, gRPC, etc.).
 *
 * <p>A {@code WorkerAdapter} bundles the dispatch side of the worker lifecycle
 * (pushing transport-neutral task dispatch items to workers) with a protocol
 * identifier so that multiple transports can coexist and be selected at runtime.
 * Runtime selection uses {@link #adapterId()} as the concrete adapter identity
 * and {@link #transportHint()} as the coarse transport family.
 * {@link #protocol()} remains an adapter implementation label and should not
 * be treated as platform capability truth.
 *
 * <p>The result callback side is transport-specific and wired externally
 * into canonical result-ingest seams such as
 * {@code TaskResultIngestChannel}.
 */
public interface WorkerAdapter extends TaskDispatchChannel {

    /**
     * Returns the adapter's implementation/protocol label.
     * Examples: {@code "websocket"}, {@code "polling"}, {@code "grpc"}.
     */
    String protocol();

    /**
     * Returns the canonical adapter identity exposed by this adapter.
     *
     * <p>This is the runtime routing truth used to bind one worker instance to
     * one concrete adapter when multiple adapters share the same transport
     * family.
     */
    default String adapterId() {
        return protocol();
    }

    /**
     * Returns the coarse worker transport family exposed by this adapter.
     *
     * <p>By default the adapter label is normalized through
     * {@link WorkerTransportHints}, which maps compatibility names such as
     * {@code websocket} or {@code pull} into stable runtime identities such as
     * {@code realtime} and {@code polling}. Adapters may override this when
     * their implementation label differs from the platform transport identity.
     */
    default String transportHint() {
        return WorkerTransportHints.normalize(protocol());
    }

    /**
     * Returns additional adapter-id compatibility aliases that should resolve
     * to this adapter.
     *
     * <p>Aliases are compatibility labels only. They may describe historical
     * protocol names such as {@code "ws"}, {@code "pull"}, or {@code "queue"},
     * but they must not be treated as separate runtime capabilities.
     */
    default Set<String> aliases() {
        return Set.of();
    }
}
