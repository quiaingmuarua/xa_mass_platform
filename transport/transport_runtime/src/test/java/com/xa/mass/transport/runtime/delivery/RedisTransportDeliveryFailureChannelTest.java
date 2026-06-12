package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.AdapterEndpoint;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        TransportDeliveryFailureEvent failure = failureEvent(command, "node-a", "route-1", "adapter unavailable");

        assertTrue(writer.handle(failure));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(command.getCommandId(), event.outcome().getDeliveryId());
        assertEquals("worker-1", event.outcome().getSelectedWorkerId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
        assertEquals("adapter unavailable", event.detail());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingFailure() throws Exception {
        DeliveryCommand first = DeliveryCommandFixtures.command("msg-1", "worker-1", "node-a");
        DeliveryCommand second = DeliveryCommandFixtures.command("msg-2", "worker-2", "node-a");

        assertTrue(writer.handle(failureEvent(first, "node-a", "route-1", "first")));
        assertFalse(writer.handle(failureEvent(second, "node-a", "route-2", "second")));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(first.getCommandId(), event.outcome().getDeliveryId());
        assertEquals("msg-1", event.outcome().getMessageId());
    }

    @Test
    void noOwnerFailureRoundTripsWithoutTargetTransportNode() throws Exception {
        DeliveryCommand command = DeliveryCommandFixtures.command("msg-no-owner", "worker-1", null);

        assertTrue(writer.handle(failureEvent(
                command,
                null,
                null,
                "transport endpoint is unavailable after assignment"
        )));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(command.getCommandId(), event.outcome().getDeliveryId());
        assertEquals("worker-1", event.outcome().getSelectedWorkerId());
        assertNull(event.outcome().getTransportNodeId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
        assertNull(event.outcome().getTransportNodeId());
    }

    private static TransportDeliveryFailureEvent failureEvent(DeliveryCommand command,
                                                              String targetTransportNodeId,
                                                              String routeKey,
                                                              String reason) {
        AdapterEndpoint endpoint = targetTransportNodeId == null ? null : new AdapterEndpoint(
                routeKey,
                targetTransportNodeId,
                "conn-" + command.getSelectedWorkerId(),
                System.currentTimeMillis() + 30_000L
        );
        DispatchOutcome outcome = DispatchOutcome.fromCommand(
                "websocket",
                "websocket",
                targetTransportNodeId,
                command,
                endpoint,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                reason
        );
        return new TransportDeliveryFailureEvent(outcome, reason);
    }
}
