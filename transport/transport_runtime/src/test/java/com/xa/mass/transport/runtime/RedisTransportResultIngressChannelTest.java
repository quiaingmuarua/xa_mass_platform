package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportResultIngressChannelTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> writerConnection;
    private StatefulRedisConnection<String, String> readerConnection;
    private String namespacePrefix;
    private RedisTransportResultIngressChannel writer;
    private RedisTransportResultIngressChannel reader;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            writerConnection = redisClient.connect();
            readerConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for result inbox test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:result-inbox:" + UUID.randomUUID();
        writer = new RedisTransportResultIngressChannel(writerConnection, namespacePrefix, 1, 50L);
        reader = new RedisTransportResultIngressChannel(readerConnection, namespacePrefix, 1, 50L);
        writer.clearForTest();
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.clearForTest();
            writer.shutdown();
        }
        if (reader != null) {
            reader.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void resultEnvelopeRoundTripsAcrossInstancesAndCompletes() throws Exception {
        TransportResultIngressEnvelope envelope = envelope("task-result-json", "msg-1");

        assertTrue(writer.ingest(envelope));
        ClaimedTransportResultIngress claimed = reader.poll(1000L);

        assertNotNull(claimed);
        assertEquals("task-result-json", claimed.envelope().getPayload());
        assertEquals("msg-1", claimed.envelope().getPartitionKey());
        assertEquals("trace-1", claimed.envelope().diagnostic("traceId"));

        reader.complete(claimed);
        assertEquals(0, writer.queuedResults());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingResult() throws Exception {
        assertTrue(writer.ingest(envelope("payload-1", "msg-1")));
        assertFalse(writer.ingest(envelope("payload-2", "msg-2")));

        ClaimedTransportResultIngress claimed = reader.poll(1000L);

        assertNotNull(claimed);
        assertEquals("msg-1", claimed.envelope().getPartitionKey());
    }

    @Test
    void claimedItemReappearsAfterVisibilityTimeoutWithoutComplete() throws Exception {
        assertTrue(writer.ingest(envelope("payload-1", "msg-1")));
        ClaimedTransportResultIngress firstClaim = reader.poll(1000L);
        assertNotNull(firstClaim);

        Thread.sleep(75L);
        ClaimedTransportResultIngress secondClaim = reader.poll(1000L);

        assertNotNull(secondClaim);
        assertEquals(firstClaim.envelope().getPayload(), secondClaim.envelope().getPayload());
        reader.complete(secondClaim);
    }

    @Test
    void missingPayloadReferenceIsDiscardedWithoutStickingInflight() throws Exception {
        writerConnection.sync().rpush(namespacePrefix + ":ready", "missing-ref");

        ClaimedTransportResultIngress claimed = reader.poll(100L);

        assertNull(claimed);
        assertEquals(0, writer.queuedResults());
    }

    @Test
    void invalidPayloadIsAckedAndDiscarded() throws Exception {
        writerConnection.sync().hset(namespacePrefix + ":payloads", "bad-ref", "{");
        writerConnection.sync().rpush(namespacePrefix + ":ready", "bad-ref");

        ClaimedTransportResultIngress claimed = reader.poll(100L);

        assertNull(claimed);
        assertEquals(0, writer.queuedResults());
    }

    private static TransportResultIngressEnvelope envelope(String payload, String partitionKey) {
        return TransportResultIngressEnvelope.received(
                payload,
                "{\"attemptId\":\"attempt-1\"}",
                partitionKey,
                Map.of("traceId", "trace-1")
        );
    }
}
