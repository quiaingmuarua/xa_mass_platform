package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
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
            Assumptions.assumeTrue(false, "Redis is not available for result ingress queue test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:result-ingress:" + UUID.randomUUID();
        writer = new RedisTransportResultIngressChannel(writerConnection, namespacePrefix, 1);
        reader = new RedisTransportResultIngressChannel(readerConnection, namespacePrefix, 1);
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
    void resultEntryRoundTripsAcrossInstances() throws Exception {
        ResultIngressEntry entry = entry("task-result-json", "corr-1");

        assertTrue(writer.ingest(entry));
        ResultIngressEntry polled = reader.poll(1000L);

        assertNotNull(polled);
        assertEquals("task-result-json", polled.message().payload());
        assertEquals("corr-1", polled.message().resultCorrelationRef());
        assertEquals("trace-1", polled.diagnostics().get("traceId"));

        assertNull(reader.poll(100L));
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingResult() throws Exception {
        assertTrue(writer.ingest(entry("payload-1", "msg-1")));
        assertFalse(writer.ingest(entry("payload-2", "msg-2")));

        ResultIngressEntry polled = reader.poll(1000L);

        assertNotNull(polled);
        assertEquals("msg-1", polled.message().resultCorrelationRef());
    }

    @Test
    void polledItemDoesNotReappearWithoutTransportAckLifecycle() throws Exception {
        assertTrue(writer.ingest(entry("payload-1", "msg-1")));
        ResultIngressEntry first = reader.poll(1000L);
        assertNotNull(first);

        Thread.sleep(75L);
        ResultIngressEntry second = reader.poll(100L);

        assertNull(second);
    }

    @Test
    void invalidReadyPayloadIsDiscarded() throws Exception {
        writerConnection.sync().rpush(namespacePrefix + ":ready", "missing-ref");

        ResultIngressEntry polled = reader.poll(100L);

        assertNull(polled);
        assertNull(reader.poll(100L));
    }

    private static ResultIngressEntry entry(String payload, String resultCorrelationRef) {
        return new ResultIngressEntry(
                resultCorrelationRef,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        resultCorrelationRef,
                        payload,
                        0L,
                        System.currentTimeMillis()
                ),
                new ResultIngressDiagnostics(Map.of("traceId", "trace-1"))
        );
    }
}
