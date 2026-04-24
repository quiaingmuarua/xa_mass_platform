package com.xa.mass.base.channel.tranporter;

import com.xa.mass.base.channel.messaging.api.MessageQueue;

/**
 * Factory for gateway-local message transporters.
 */
public class MessageTransporterFactory {

    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";

    public static <I, O> MessageTransporter<I, O> createQueueBased(MessageQueue<I> inputQueue,
                                                                   MessageQueue<O> outputQueue) {
        return new QueueBasedMessageTransporter<>(inputQueue, outputQueue);
    }

    public static <I, O> MessageTransporter<I, O> createMultiLevel() {
        return new MultiLevelMessageTransporter<>();
    }

    public static <I, O> MessageTransporter<I, O> createApiBased(String inputApiUrl,
                                                                 String outputApiUrl,
                                                                 String apiKey) {
        throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
    }

    public enum TransporterType {
        QUEUE_BASED,
        MULTI_LEVEL,
        API_BASED
    }
}
