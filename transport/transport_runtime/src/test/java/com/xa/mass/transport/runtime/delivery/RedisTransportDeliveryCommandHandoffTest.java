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
    private RedisTransportDeliveryCommandHandoff nodeOneConsumer;
    private RedisTransportDeliveryCommandHandoff nodeTwoConsumer;

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
        producer = new RedisTransportDeliveryCommandHandoff(producerConnection, namespacePrefix, "producer", 2);
        nodeOneConsumer = new RedisTransportDeliveryCommandHandoff(consumerOneConnection, namespacePrefix, "node-1", 2);
        nodeTwoConsumer = new RedisTransportDeliveryCommandHandoff(consumerTwoConnection, namespacePrefix, "node-2", 2);
    }

    @AfterEach
    void tearDown() {
        if (producerConnection != null && producerConnection.isOpen()) {
            producerConnection.sync().keys(namespacePrefix + ":*").forEach(key -> producerConnection.sync().del(key));
        }
        if (producer != null) {
            producer.shutdown();
        }
        if (nodeOneConsumer != null) {
            nodeOneConsumer.shutdown();
        }
        if (nodeTwoConsumer != null) {
            nodeTwoConsumer.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void offerAndPollRoundTripsByBucketDerivedQueueKey() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        DeliveryQueueOffer offer = DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(offer).stream().map(outcome -> outcome.getStatus()).toList());

        DeliveryCommandBatch nodeOne = nodeOneConsumer.poll(500L);

        assertNotNull(nodeOne);
        assertEquals(DeliveryCommandFixtures.queueKey(), nodeOne.deliveryQueueKey());
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(nodeOne));
    }

    @Test
    void sharedDeliveryQueueKeyPreservesSelectedWorkerCommands() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.claimConsumerForTest("bucket-1", "worker-2", "node-2");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-2", "worker-2", "node-2")
        ));
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        DeliveryCommandBatch nodeOne = nodeOneConsumer.poll(500L);
        DeliveryCommandBatch nodeTwo = nodeTwoConsumer.poll(500L);

        assertNotNull(nodeOne);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(nodeOne));
        assertEquals("worker-1", nodeOne.items().getFirst().getSelectedWorkerId());
        assertNotNull(nodeTwo);
        assertEquals(List.of("msg-2"), DeliveryCommandFixtures.messages(nodeTwo));
        assertEquals("worker-2", nodeTwo.items().getFirst().getSelectedWorkerId());
    }

    @Test
    void boundedOfferUpdatesQueueCatalogWithoutLaneOrNodeKeys() {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        List<String> keys = producerConnection.sync().keys(namespacePrefix + ":*");

        assertTrue(keys.stream().anyMatch(key -> key.contains(":q:") && key.endsWith(":commands")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":command-retention-deadlines")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":queues")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":consumer:") && key.endsWith(":ready-commands")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":queue-consumers:")));
        assertTrue(keys.stream().anyMatch(key -> key.contains(":selected-worker-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":lane:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-lanes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":route:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-routes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":owner-shard:")));
    }

    @Test
    void fullLaneReturnsBackpressureWithoutSleepingProducer() {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.claimConsumerForTest("bucket-1", "worker-2", "node-1");
        producer.claimConsumerForTest("bucket-1", "worker-3", "node-1");
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
    void missingSelectedWorkerConsumerReturnsNoEndpointWithoutQueueing() throws Exception {
        assertEquals(List.of(DispatchOutcomeStatus.NO_ENDPOINT),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());

        assertNull(nodeOneConsumer.poll(50L));
        assertEquals(0, producer.queuedBatches(DeliveryCommandFixtures.queueKey()));
    }

    @Test
    void nonOwningConsumerCannotDestructivelyClaimSelectedWorkerCommand() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertNull(nodeTwoConsumer.poll(50L));
        DeliveryCommandBatch nodeOne = nodeOneConsumer.poll(500L);

        assertNotNull(nodeOne);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(nodeOne));
    }

    @Test
    void completeAcknowledgesClaimedCommand() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        DeliveryCommandBatch batch = nodeOneConsumer.poll(500L);

        assertNotNull(batch);
        assertEquals(1L, producer.inflightReferencesForTest("node-1"));
        nodeOneConsumer.complete(batch, List.of());

        assertEquals(0, producer.queuedBatches(DeliveryCommandFixtures.queueKey()));
        assertEquals(0L, producer.inflightReferencesForTest("node-1"));
        assertNull(nodeOneConsumer.poll(50L));
    }

    @Test
    void pollAtomicallyClaimsReadyReferenceIntoInflightBeforeMaterializing() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertEquals(1L, producer.readyReferencesForTest("node-1"));
        DeliveryCommandBatch batch = nodeOneConsumer.poll(500L);

        assertNotNull(batch);
        assertEquals(0L, producer.readyReferencesForTest("node-1"));
        assertEquals(1L, producer.inflightReferencesForTest("node-1"));
        assertEquals(1, producer.queuedBatches(DeliveryCommandFixtures.queueKey()));
        nodeOneConsumer.complete(batch, List.of());
    }

    @Test
    void uncompletedClaimReturnsToReadyAfterVisibilityTimeout() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        DeliveryCommandBatch first = nodeOneConsumer.poll(500L);

        assertNotNull(first);
        assertEquals(1L, producer.inflightReferencesForTest("node-1"));
        producer.expireInflightForTest("node-1");
        DeliveryCommandBatch redelivered = nodeOneConsumer.poll(500L);

        assertNotNull(redelivered);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(redelivered));
        assertEquals(1L, producer.inflightReferencesForTest("node-1"));
        nodeOneConsumer.complete(redelivered, List.of());
    }

    @Test
    void movedOwnerRemovesOldInflightAndForwardsToCurrentConsumer() throws Exception {
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-2");

        assertNull(nodeOneConsumer.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest("node-1"));
        assertEquals(1L, producer.readyReferencesForTest("node-2"));
        DeliveryCommandBatch nodeTwo = nodeTwoConsumer.poll(500L);

        assertNotNull(nodeTwo);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(nodeTwo));
        assertEquals(1L, producer.inflightReferencesForTest("node-2"));
        nodeTwoConsumer.complete(nodeTwo, List.of());
    }

    @Test
    void missingPayloadDoesNotLeaveInflightClaimStuck() throws Exception {
        DeliveryCommandBatch commandBatch = DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );
        producer.claimConsumerForTest("bucket-1", "worker-1", "node-1");
        producer.offer(new DeliveryQueueOffer(DeliveryCommandFixtures.queueKey(), commandBatch.items()));
        producer.deleteCommandPayloadForTest(
                DeliveryCommandFixtures.queueKey(),
                commandBatch.items().getFirst().getCommandId()
        );

        assertNull(nodeOneConsumer.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest("node-1"));
        assertEquals(0L, producer.readyReferencesForTest("node-1"));
    }

    @Test
    void invalidReadyReferenceDoesNotLeaveInflightClaimStuck() throws Exception {
        producer.pushReadyReferenceForTest("node-1", "not-a-valid-reference");

        assertNull(nodeOneConsumer.poll(50L));
        assertEquals(0L, producer.inflightReferencesForTest("node-1"));
        assertEquals(0L, producer.readyReferencesForTest("node-1"));
    }

    @Test
    void staleReleaseDoesNotRemoveNewConsumerEvidenceOnSameQueueConsumer() {
        producer.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "node-1",
                "conn-old",
                "websocket",
                System.currentTimeMillis() + 30_000L
        ));
        producer.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "node-1",
                "conn-new",
                "websocket",
                System.currentTimeMillis() + 30_000L
        ));

        producer.releaseConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                "worker-1",
                "node-1",
                "conn-old",
                "websocket",
                0L
        ));

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }
}
