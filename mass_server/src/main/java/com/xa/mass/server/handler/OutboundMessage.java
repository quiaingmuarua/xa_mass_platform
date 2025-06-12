package com.xa.mass.server.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelFuture;

import java.util.function.Consumer;

/**
 * OutboundMessage represents a message to be sent over a Netty channel.
 * Includes optional callbacks for success and failure handling.
 */
public class OutboundMessage {
    private final String messageId;
    private final String payload;
    private final Channel channel;

    private final Runnable onSuccess;
    private final Consumer<Throwable> onFailure;
    private final Runnable callback;

    public OutboundMessage(String messageId, String payload, Channel channel,
                           Runnable onSuccess,
                           Consumer<Throwable> onFailure,
                           Runnable callback) {
        this.messageId = messageId;
        this.payload = payload;
        this.channel = channel;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.callback = callback;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getPayload() {
        return payload;
    }

    public Channel getChannel() {
        return channel;
    }

    public Runnable getOnSuccess() {
        return onSuccess;
    }

    public Consumer<Throwable> getOnFailure() {
        return onFailure;
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "messageId='" + messageId + '\'' +
                ", payload='" + payload + '\'' +
                ", channel=" + (channel != null ? channel.remoteAddress() : "null") +
                '}';
    }

    /**
     * Sends this message over the associated Netty channel.
     * Handles success and failure callbacks if provided.
     */
    public void send() {
        channel.writeAndFlush(payload).addListener((ChannelFuture future) -> {
            try {
                if (future.isSuccess()) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else {
                    if (onFailure != null) {
                        onFailure.accept(future.cause());
                    }
                }
            } finally {
                if (callback != null) {
                    callback.run();
                }
            }
        });
    }
}
