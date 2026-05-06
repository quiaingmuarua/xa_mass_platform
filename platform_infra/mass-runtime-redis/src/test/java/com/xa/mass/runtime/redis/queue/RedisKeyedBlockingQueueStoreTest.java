package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyedBlockingQueueStoreTest {

    @Test
    void mapsEnqueuedOfferScriptResponse() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(List.of("ENQUEUED"));

        assertEquals(KeyedQueueOfferResult.enqueued(), result);
    }

    @Test
    void mapsPerKeyBackpressureOfferScriptResponse() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(
                List.of("BACKPRESSURE_KEY", "queue is full")
        );

        assertEquals(KeyedQueueOfferResult.backpressureRejected("queue is full"), result);
    }

    @Test
    void mapsGlobalBackpressureOfferScriptResponse() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(
                List.of("BACKPRESSURE_GLOBAL", "runtime backlog is full")
        );

        assertEquals(KeyedQueueOfferResult.backpressureRejected("runtime backlog is full"), result);
    }

    @Test
    void mapsInvalidOfferScriptResponse() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(
                List.of("INVALID", "key and entry must not be null")
        );

        assertEquals(KeyedQueueOfferResult.invalid("key and entry must not be null"), result);
    }

    @Test
    void mapsUnexpectedOfferScriptResponseToUnavailable() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(List.of("UNIMPLEMENTED"));

        assertEquals(
                KeyedQueueOfferResult.unavailable("queue store returned unsupported response: UNIMPLEMENTED"),
                result
        );
    }

    @Test
    void stringCodecRoundTripsSimpleEntry() {
        RedisKeyedQueueCodec<String, String> codec = new RedisKeyedQueueCodec<>() {
            @Override
            public String encodeKeyPart(String key) {
                return key;
            }

            @Override
            public String decodeKeyPart(String encodedKeyPart) {
                return encodedKeyPart;
            }

            @Override
            public byte[] encodeValue(KeyedQueueEntry<String> entry) {
                return entry.value().getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public KeyedQueueEntry<String> decodeValue(byte[] bytes) {
                return new KeyedQueueEntry<>(new String(bytes, StandardCharsets.UTF_8), 0L);
            }
        };

        RedisKeyedQueueOptions options = new RedisKeyedQueueOptions(
                100,
                Duration.ofMillis(50),
                Duration.ofMillis(100)
        );

        assertEquals("worker-1", codec.encodeKeyPart("worker-1"));
        assertEquals(100, options.maxQueuedItems());
    }
}
