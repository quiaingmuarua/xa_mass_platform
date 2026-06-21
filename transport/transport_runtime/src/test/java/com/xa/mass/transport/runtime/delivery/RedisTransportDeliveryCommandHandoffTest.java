package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcomeStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportDeliveryCommandHandoffTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> producerConnection;
    private StatefulRedisConnection<String, String> consumerOneConnection;
    private StatefulRedisConnection<String, String> consumerTwoConnection;
    private String namespacePrefix;
    private RedisTransportDeliveryCommandHandoff producer;
    private RedisTransportDeliveryCommandHandoff consumerOne;
    private RedisTransportDeliveryCommandHandoff consumerTwo;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            producerConnection = redisClient.connect();
            consumerOneConnection = redisClient.connect();
            consumerTwoConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for delivery command handoff test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-delivery-command:" + UUID.randomUUID();
        producer = new RedisTransportDeliveryCommandHandoff(producerConnection, namespacePrefix, 2);
        consumerOne = new RedisTransportDeliveryCommandHandoff(consumerOneConnection, namespacePrefix, 2);
        consumerTwo = new RedisTransportDeliveryCommandHandoff(consumerTwoConnection, namespacePrefix, 2);
    }

    @AfterEach
    void tearDown() {
        if (producerConnection != null && producerConnection.isOpen()) {
            producerConnection.sync().keys(namespacePrefix + ":*").forEach(key -> producerConnection.sync().del(key));
        }
        if (producer != null) {
            producer.shutdown();
        }
        if (consumerOne != null) {
            consumerOne.shutdown();
        }
        if (consumerTwo != null) {
            consumerTwo.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void offerAndPollRoundTripsByAdapterMailbox() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
                )).stream().map(outcome -> outcome.getStatus()).toList());

        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(DeliveryCommandFixtures.mailboxKey(), batch.adapterMailboxKey());
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(batch));
        assertEquals("worker-1", batch.items().getFirst().getSelectedWorkerId());
    }

    @Test
    void offerWithoutMailboxConsumerReturnsUnavailable() {
        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void boundedOfferUsesMailboxKeysWithoutWorkerLaneOrNodeKeys() {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));
        List<String> keys = producerConnection.sync().keys(namespacePrefix + ":*");

        assertTrue(keys.stream().anyMatch(key -> key.contains(":mailbox:") && key.endsWith(":commands")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":command-retention-deadlines")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":queues")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":mailbox:") && key.endsWith(":ready-commands")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":mailbox-consumers")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":mailbox-consumer-deadlines")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":selected-worker-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":ready-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":inflight-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":queue-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":lane:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-lanes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":route:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-routes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":owner-shard:")));
    }

    @Test
    void fullMailboxQueueReturnsBackpressureWithoutSleepingProducer() {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-2", "worker-2", "ignored")
        ));

        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-3", "worker-3", "ignored")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void nonOwningConsumerCannotDestructivelyClaimMailboxCommand() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));

        assertNull(consumerTwo.poll(50L));
        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(batch));
    }

    @Test
    void completeAcknowledgesClaimedCommand() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));
        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        consumerOne.complete(batch, List.of());

        assertEquals(0, producer.queuedBatches(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertNull(consumerOne.poll(50L));
    }

    @Test
    void pollAtomicallyClaimsReadyReferenceIntoInflightBeforeMaterializing() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));

        assertEquals(1L, producer.readyReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(1, producer.queuedBatches(DeliveryCommandFixtures.mailboxKey()));
        consumerOne.complete(batch, List.of());
    }

    @Test
    void uncompletedClaimReturnsToReadyAfterVisibilityTimeout() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));
        DeliveryCommandBatch first = consumerOne.poll(500L);

        assertNotNull(first);
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        producer.expireInflightForTest(DeliveryCommandFixtures.mailboxKey());
        DeliveryCommandBatch redelivered = consumerOne.poll(500L);

        assertNotNull(redelivered);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(redelivered));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        consumerOne.complete(redelivered, List.of());
    }

    @Test
    void mailboxConsumerTakeoverForwardsReadyReferenceToCurrentConsumer() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        ));
        consumerTwo.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-2");

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(1L, producer.readyReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        DeliveryCommandBatch batch = consumerTwo.poll(500L);

        assertNotNull(batch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(batch));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        consumerTwo.complete(batch, List.of());
    }

    @Test
    void staleReleaseDoesNotRemoveNewMailboxConsumerLease() {
        consumerOne.claimMailboxConsumer(new AdapterMailboxConsumerLease(
                DeliveryCommandFixtures.mailboxKey(),
                "consumer-old",
                1L,
                System.currentTimeMillis() + 30_000L
        ));
        consumerOne.claimMailboxConsumer(new AdapterMailboxConsumerLease(
                DeliveryCommandFixtures.mailboxKey(),
                "consumer-new",
                2L,
                System.currentTimeMillis() + 30_000L
        ));

        consumerOne.releaseMailboxConsumer(new AdapterMailboxConsumerLease(
                DeliveryCommandFixtures.mailboxKey(),
                "consumer-old",
                1L,
                0L
        ));

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void missingPayloadDoesNotLeaveInflightClaimStuck() throws Exception {
        DeliveryCommandBatch commandBatch = DeliveryCommandFixtures.batch(
                "ignored",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "ignored")
        );
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.offer(DeliveryCommandFixtures.offer(commandBatch.items().getFirst()));
        producer.deleteCommandPayloadForTest(
                DeliveryCommandFixtures.mailboxKey(),
                commandBatch.items().getFirst().getCommandId()
        );

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
    }

    @Test
    void invalidReadyReferenceDoesNotLeaveInflightClaimStuck() throws Exception {
        consumerOne.claimConsumerForTest(DeliveryCommandFixtures.mailboxKey(), "consumer-1");
        producer.pushReadyReferenceForTest(DeliveryCommandFixtures.mailboxKey(), "not-a-valid-reference");

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.mailboxKey()));
    }
}
