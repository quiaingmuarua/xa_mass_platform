package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTaskResultIngestChannelTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> writerConnection;
    private StatefulRedisConnection<String, String> readerConnection;
    private String namespacePrefix;
    private RedisTaskResultIngestChannel writer;
    private RedisTaskResultIngestChannel reader;

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
        writer = new RedisTaskResultIngestChannel(writerConnection, namespacePrefix, 1);
        reader = new RedisTaskResultIngestChannel(readerConnection, namespacePrefix, 1);
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
    void resultEnvelopeRoundTripsAcrossInstances() throws Exception {
        TransportResultEnvelope envelope = envelope("task-1", "msg-1");

        assertTrue(writer.ingest(envelope));
        TransportResultEnvelope decoded = reader.pollEnvelope(1000L);

        assertNotNull(decoded);
        assertEquals("websocket", decoded.getAdapterId());
        assertEquals("route-1", decoded.getRouteKey());
        assertEquals("attempt-1", decoded.getAttemptId());
        assertEquals("task-1", decoded.getReport().getTaskId());
        assertEquals(Map.of("value", "ok"), decoded.getReport().getOutput());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingResult() throws Exception {
        assertTrue(writer.ingest(envelope("task-1", "msg-1")));
        assertFalse(writer.ingest(envelope("task-1", "msg-2")));

        TransportResultEnvelope decoded = reader.pollEnvelope(1000L);

        assertNotNull(decoded);
        assertEquals("msg-1", decoded.getMessageId());
    }

    @Test
    void reportOnlyIngressIsRejectedForDistributedInbox() {
        assertFalse(writer.ingest(new TaskResultReport(
                "task-1",
                "msg-1",
                true,
                "done",
                null,
                Map.of()
        )));
        assertEquals(0, writer.queuedResults());
    }

    private static TransportResultEnvelope envelope(String taskId, String messageId) {
        return new TransportResultEnvelope(
                "websocket",
                "route-1",
                "attempt-1",
                "lease-1",
                "trace-1",
                new TaskResultReport(
                        taskId,
                        messageId,
                        true,
                        "done",
                        null,
                        Map.of("value", "ok")
                )
        );
    }
}
