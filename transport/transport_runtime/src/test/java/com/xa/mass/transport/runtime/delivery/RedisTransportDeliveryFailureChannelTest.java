package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportDeliveryFailureChannelTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> writerConnection;
    private StatefulRedisConnection<String, String> readerConnection;
    private String namespacePrefix;
    private RedisTransportDeliveryFailureChannel writer;
    private RedisTransportDeliveryFailureChannel reader;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            writerConnection = redisClient.connect();
            readerConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for delivery failure test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:delivery-failure:" + UUID.randomUUID();
        writer = new RedisTransportDeliveryFailureChannel(writerConnection, namespacePrefix, 1);
        reader = new RedisTransportDeliveryFailureChannel(readerConnection, namespacePrefix, 1);
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
    void failureEventRoundTripsAcrossInstances() throws Exception {
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-a");
        DispatchOutcome outcome = DispatchOutcome.noEndpoint(command, "adapter unavailable");

        assertTrue(writer.handle(command, outcome, "adapter unavailable"));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(command.getCommandId(), event.command().getCommandId());
        assertEquals("worker-1", event.command().getSelectedWorkerId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
        assertEquals("adapter unavailable", event.detail());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingFailure() throws Exception {
        DeliveryCommand first = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-a");
        DeliveryCommand second = DeliveryCommandFixtures.command("msg-2", "worker-2", "node-a");

        assertTrue(writer.handle(first, DispatchOutcome.noEndpoint(first, "first"), "first"));
        assertFalse(writer.handle(second, DispatchOutcome.noEndpoint(second, "second"), "second"));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(first.getCommandId(), event.command().getCommandId());
        assertEquals("msg-1", event.command().getPayload().messageId());
    }
}
