package com.xa.mass.transport.runtime.delivery;

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
        DispatchRoutingItem item = DispatchRoutingFixtures.item("msg-1", "worker-1");
        TransportDeliveryFailureEvent failure = failureEvent(item, "adapter unavailable");

        assertTrue(writer.handle(failure));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(item.deliveryId(), event.outcome().getDeliveryId());
        assertEquals("worker-1", event.outcome().getSelectedWorkerId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
        assertEquals("adapter unavailable", event.detail());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingFailure() throws Exception {
        DispatchRoutingItem first = DispatchRoutingFixtures.item("msg-1", "worker-1");
        DispatchRoutingItem second = DispatchRoutingFixtures.item("msg-2", "worker-2");

        assertTrue(writer.handle(failureEvent(first, "first")));
        assertFalse(writer.handle(failureEvent(second, "second")));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(first.deliveryId(), event.outcome().getDeliveryId());
        assertEquals(first.correlationRef(), event.outcome().getCorrelationRef());
    }

    @Test
    void noOwnerFailureRoundTripsWithoutTargetTransportNode() throws Exception {
        DispatchRoutingItem item = DispatchRoutingFixtures.item("msg-no-owner", "worker-1");

        assertTrue(writer.handle(failureEvent(
                item,
                "transport endpoint is unavailable after assignment"
        )));

        TransportDeliveryFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals(item.deliveryId(), event.outcome().getDeliveryId());
        assertEquals("worker-1", event.outcome().getSelectedWorkerId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
    }

    private static TransportDeliveryFailureEvent failureEvent(DispatchRoutingItem item,
                                                              String reason) {
        DispatchOutcome outcome = DispatchOutcomeFactory.fromItem(
                item,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                reason
        );
        return new TransportDeliveryFailureEvent(outcome, reason);
    }
}
