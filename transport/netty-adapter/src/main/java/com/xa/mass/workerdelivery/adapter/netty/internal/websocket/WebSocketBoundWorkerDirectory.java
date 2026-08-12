package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class WebSocketBoundWorkerDirectory
        implements DeliveryCommandTarget {

    private final ConcurrentMap<String, Channel> connections =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;

    public WebSocketBoundWorkerDirectory(WorkerDeliveryCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    void activate(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        Channel previous = connections.put(workerId, requiredChannel);
        if (previous != null && previous != requiredChannel) {
            WebSocketCloseReason.REPLACED.close(previous);
        }
    }

    void deactivate(String workerId, Channel expectedChannel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        connections.remove(workerId, expectedChannel);
    }

    @Override
    public DeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        Channel channel = connections.get(workerId);
        if (channel == null) {
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isActive()) {
            removeAndClose(
                    workerId,
                    channel,
                    WebSocketCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = channel.writeAndFlush(new TextWebSocketFrame(
                    codec.encodeDeliveryCommand(command)
            ));
        } catch (RuntimeException error) {
            removeAndClose(
                    workerId,
                    channel,
                    WebSocketCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(
                        workerId,
                        channel,
                        WebSocketCloseReason.TRANSPORT_ERROR
                );
            }
        });
        return DeliveryAttempt.STARTED;
    }

    void close(
            String workerId,
            Channel expectedChannel,
            WebSocketCloseReason reason
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        Objects.requireNonNull(reason, "reason");
        connections.remove(workerId, expectedChannel);
        reason.close(expectedChannel);
    }

    public int activeConnectionCount() {
        return connections.size();
    }

    private void removeAndClose(
            String workerId,
            Channel expectedChannel,
            WebSocketCloseReason reason
    ) {
        connections.remove(workerId, expectedChannel);
        reason.close(expectedChannel);
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
    }
}
