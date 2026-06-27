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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportDispatchHandoffTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> producerConnection;
    private StatefulRedisConnection<String, String> consumerOneConnection;
    private StatefulRedisConnection<String, String> consumerTwoConnection;
    private String namespacePrefix;
    private RedisTransportDispatchHandoff producer;
    private RedisTransportDispatchHandoff consumerOne;
    private RedisTransportDispatchHandoff consumerTwo;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            producerConnection = redisClient.connect();
            consumerOneConnection = redisClient.connect();
            consumerTwoConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for dispatch handoff test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-dispatch:" + UUID.randomUUID();
        producer = new RedisTransportDispatchHandoff(producerConnection, namespacePrefix, 2);
        consumerOne = new RedisTransportDispatchHandoff(consumerOneConnection, namespacePrefix, 2);
        consumerTwo = new RedisTransportDispatchHandoff(consumerTwoConnection, namespacePrefix, 2);
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
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");

        assertEquals(List.of(DispatchOutcomeStatus.QUEUED),
                producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());

        List<DispatchMessage> batch = consumerOne.poll(DispatchMessageFixtures.mailboxKey(), 64, 500L);

        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(batch));
        assertEquals("worker-1", batch.getFirst().selectedWorkerId());
    }

    @Test
    void offerWithoutMailboxConsumerReturnsUnavailable() {
        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE),
                producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void boundedOfferUsesMailboxKeysWithoutWorkerLaneOrNodeKeys() {
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");
        producer.offer(DispatchMessageFixtures.batch(
                DispatchMessageFixtures.item("msg-1", "worker-1")
        ));
        List<String> keys = producerConnection.sync().keys(namespacePrefix + ":*");

        assertTrue(keys.stream().anyMatch(key -> key.contains(":ready:q:")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":mailbox-consumers")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":mailbox-consumer-deadlines")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":selected-worker-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":ready-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":worker:") && key.endsWith(":inflight-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":command-retention-deadlines")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":commands")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":inflight-commands")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":queue-consumers:")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":lane:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-lanes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":route:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-routes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":owner-shard:")));
    }

    @Test
    void fullMailboxQueueReturnsBackpressureWithoutSleepingProducer() {
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");
        producer.offer(DispatchMessageFixtures.batch(DispatchMessageFixtures.item("msg-1", "worker-1")));
        producer.offer(DispatchMessageFixtures.batch(DispatchMessageFixtures.item("msg-2", "worker-2")));

        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE),
                producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-3", "worker-3")
                )).stream().map(outcome -> outcome.getStatus()).toList());
    }

    @Test
    void nonOwningConsumerCannotDestructivelyClaimMailboxItem() throws Exception {
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");
        producer.offer(DispatchMessageFixtures.batch(DispatchMessageFixtures.item("msg-1", "worker-1")));

        assertTrue(consumerTwo.poll(DispatchMessageFixtures.mailboxKey(), 64, 50L).isEmpty());
        List<DispatchMessage> batch = consumerOne.poll(DispatchMessageFixtures.mailboxKey(), 64, 500L);

        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(batch));
    }

    @Test
    void pollIsDestructiveAndDoesNotRequireAck() throws Exception {
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");
        producer.offer(DispatchMessageFixtures.batch(DispatchMessageFixtures.item("msg-1", "worker-1")));

        List<DispatchMessage> batch = consumerOne.poll(DispatchMessageFixtures.mailboxKey(), 64, 500L);

        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(batch));
        assertEquals(0, producer.queuedBatches(DispatchMessageFixtures.mailboxKey()));
        assertTrue(consumerOne.poll(DispatchMessageFixtures.mailboxKey(), 64, 50L).isEmpty());
    }

    @Test
    void corruptReadyValueIsDroppedWithoutBlockingLaterItems() throws Exception {
        consumerOne.claimConsumerForTest(DispatchMessageFixtures.mailboxKey(), "consumer-1");
        producer.pushRawReadyValueForTest(DispatchMessageFixtures.mailboxKey(), "not-json");
        producer.pushReadyItemForTest(DispatchMessageFixtures.mailboxKey(),
                DispatchMessageFixtures.item("msg-1", "worker-1"));

        List<DispatchMessage> batch = consumerOne.poll(DispatchMessageFixtures.mailboxKey(), 64, 500L);

        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(batch));
    }
}
