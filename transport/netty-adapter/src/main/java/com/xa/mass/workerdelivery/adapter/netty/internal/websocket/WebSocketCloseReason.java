package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;

enum WebSocketCloseReason {
    ADAPTER_STOPPING(1001, "Adapter is stopping"),
    BINARY_UNSUPPORTED(1003, "Binary frames are unsupported"),
    INVALID_REPORT(1007, "Invalid Worker result"),
    IDENTITY_REQUIRED(1008, "Worker must identify first"),
    VERIFICATION_IN_PROGRESS(1008, "Worker route verification is in progress"),
    VERIFICATION_FAILED(1008, "Worker route verification failed"),
    REPLACED(1008, "Replaced by a newer Worker connection"),
    RESULT_BUFFER_FULL(1013, "Worker result buffer is full"),
    TRANSPORT_ERROR(1011, "Worker transport failed");

    private final int closeCode;
    private final String message;

    WebSocketCloseReason(int closeCode, String message) {
        this.closeCode = closeCode;
        this.message = message;
    }

    void close(Channel channel) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.writeAndFlush(new CloseWebSocketFrame(
                    closeCode,
                    message
            )).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException ignored) {
            closeBestEffort(channel);
        }
    }

    private static void closeBestEffort(Channel channel) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort.
        }
    }
}
