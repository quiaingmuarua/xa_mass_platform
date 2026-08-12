package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionHandlerFactory;
import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Finite physical-protocol boundary below the shared Adapter mechanism.
 *
 * <p>This is an internal sealed boundary, not a protocol SPI.
 */
public sealed interface AdapterNetworkProtocol
        permits WebSocketNetworkProtocol, SocketNetworkProtocol {

    static AdapterNetworkProtocol webSocket(Duration sendTimeLimit) {
        return new WebSocketNetworkProtocol(sendTimeLimit);
    }

    static AdapterNetworkProtocol socket(Duration sendTimeLimit) {
        return new SocketNetworkProtocol(sendTimeLimit);
    }

    void installPipeline(
            SocketChannel channel,
            WorkerConnectionHandlerFactory handlers,
            BooleanSupplier acceptingConnections
    );

    void close(Channel channel, AdapterConnectionCloseReason reason);
}
