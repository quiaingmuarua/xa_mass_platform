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
    void offerAndPollRoundTripsByBucketDerivedQueueKey() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        DeliveryQueueOffer offer = DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(offer).stream().map(outcome -> outcome.getStatus()).toList());

        DeliveryCommandBatch consumerOneBatch = consumerOne.poll(500L);

        assertNotNull(consumerOneBatch);
        assertEquals(DeliveryCommandFixtures.queueKey(), consumerOneBatch.deliveryQueueKey());
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(consumerOneBatch));
    }

    @Test
    void sharedDeliveryQueueKeyPreservesSelectedWorkerCommands() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        consumerTwo.claimConsumerForTest("bucket-1", "worker-2", "lease-2");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-2", "worker-2", "node-2")
        ));
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        DeliveryCommandBatch consumerOneBatch = consumerOne.poll(500L);
        DeliveryCommandBatch consumerTwoBatch = consumerTwo.poll(500L);

        assertNotNull(consumerOneBatch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(consumerOneBatch));
        assertEquals("worker-1", consumerOneBatch.items().getFirst().getSelectedWorkerId());
        assertNotNull(consumerTwoBatch);
        assertEquals(List.of("msg-2"), DeliveryCommandFixtures.messages(consumerTwoBatch));
        assertEquals("worker-2", consumerTwoBatch.items().getFirst().getSelectedWorkerId());
    }

    @Test
    void boundedOfferUpdatesQueueCatalogWithoutWorkerLaneOrNodeKeys() {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        List<String> keys = producerConnection.sync().keys(namespacePrefix + ":*");

        assertTrue(keys.stream().anyMatch(key -> key.contains(":q:") && key.endsWith(":commands")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":command-retention-deadlines")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":queues")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":q:") && key.endsWith(":ready-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":ready-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":inflight-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":consumer:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":queue-consumers:")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":selected-worker-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":lane:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-lanes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":route:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-routes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":owner-shard:")));
    }

    @Test
    void fullBucketQueueReturnsBackpressureWithoutSleepingProducer() {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        consumerOne.claimConsumerForTest("bucket-1", "worker-2", "lease-2");
        consumerOne.claimConsumerForTest("bucket-1", "worker-3", "lease-3");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-2", "worker-2", "node-1")
        ));

        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-3", "worker-3", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void offerQueuesWithoutProducerSideSelectedWorkerConsumerLookup() throws Exception {
        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());

        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(batch));
        assertEquals("worker-1", batch.items().getFirst().getSelectedWorkerId());
    }

    @Test
    void nonOwningConsumerCannotDestructivelyClaimSelectedWorkerCommand() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertNull(consumerTwo.poll(50L));
        DeliveryCommandBatch consumerOneBatch = consumerOne.poll(500L);

        assertNotNull(consumerOneBatch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(consumerOneBatch));
    }

    @Test
    void completeAcknowledgesClaimedCommand() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        consumerOne.complete(batch, List.of());

        assertEquals(0, producer.queuedBatches(DeliveryCommandFixtures.queueKey()));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertNull(consumerOne.poll(50L));
    }

    @Test
    void pollAtomicallyClaimsReadyReferenceIntoInflightBeforeMaterializing() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertEquals(1L, producer.readyReferencesForTest(DeliveryCommandFixtures.queueKey()));
        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertEquals(1, producer.queuedBatches(DeliveryCommandFixtures.queueKey()));
        consumerOne.complete(batch, List.of());
    }

    @Test
    void uncompletedClaimReturnsToReadyAfterVisibilityTimeout() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        DeliveryCommandBatch first = consumerOne.poll(500L);

        assertNotNull(first);
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        producer.expireInflightForTest(DeliveryCommandFixtures.queueKey());
        DeliveryCommandBatch redelivered = consumerOne.poll(500L);

        assertNotNull(redelivered);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(redelivered));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        consumerOne.complete(redelivered, List.of());
    }

    @Test
    void movedOwnerRemovesOldInflightAndForwardsToCurrentConsumer() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-old");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        consumerTwo.claimConsumerForTest("bucket-1", "worker-1", "lease-new");

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertEquals(1L, producer.readyReferencesForTest(DeliveryCommandFixtures.queueKey()));
        DeliveryCommandBatch consumerTwoBatch = consumerTwo.poll(500L);

        assertNotNull(consumerTwoBatch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(consumerTwoBatch));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        consumerTwo.complete(consumerTwoBatch, List.of());
    }

    @Test
    void missingConsumerEvidenceStillMaterializesForFinalHopNoEndpointOutcome() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        producer.releaseConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "lease-1",
                0L
        ));

        DeliveryCommandBatch batch = consumerOne.poll(500L);

        assertNotNull(batch);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(batch));
        assertEquals(1L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        consumerOne.complete(batch, List.of());
    }

    @Test
    void missingPayloadDoesNotLeaveInflightClaimStuck() throws Exception {
        DeliveryCommandBatch commandBatch = DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.offer(new DeliveryQueueOffer(DeliveryCommandFixtures.queueKey(), commandBatch.items()));
        producer.deleteCommandPayloadForTest(
                DeliveryCommandFixtures.queueKey(),
                commandBatch.items().getFirst().getCommandId()
        );

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.queueKey()));
    }

    @Test
    void invalidReadyReferenceDoesNotLeaveInflightClaimStuck() throws Exception {
        consumerOne.claimConsumerForTest("bucket-1", "worker-1", "lease-1");
        producer.pushReadyReferenceForTest(DeliveryCommandFixtures.queueKey(), "not-a-valid-reference");

        assertNull(consumerOne.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest(DeliveryCommandFixtures.queueKey()));
        assertEquals(0L, producer.readyReferencesForTest(DeliveryCommandFixtures.queueKey()));
    }

    @Test
    void staleReleaseDoesNotRemoveNewConsumerEvidenceOnSameQueueConsumer() {
        consumerOne.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "conn-old",
                System.currentTimeMillis() + 30_000L
        ));
        consumerOne.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "conn-new",
                System.currentTimeMillis() + 30_000L
        ));

        consumerOne.releaseConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "conn-old",
                0L
        ));

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }
}
