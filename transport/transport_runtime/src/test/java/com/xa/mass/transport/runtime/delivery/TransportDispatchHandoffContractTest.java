package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcomeStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportDispatchHandoffContractTest {

    @Test
    void inMemoryHandoffSatisfiesContract() throws Exception {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(2);

        verifyContract(new HandoffFixture(handoff, handoff, handoff));
    }

    @Test
    void redisHandoffSatisfiesContract() throws Exception {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        RedisClient redisClient = RedisClient.create(redisUri);
        StatefulRedisConnection<String, String> producerConnection = null;
        StatefulRedisConnection<String, String> consumerConnection = null;
        try {
            producerConnection = redisClient.connect();
            consumerConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for dispatch handoff contract test: "
                    + ex.getMessage());
        }
        String namespace = "xa:mass:test:dispatch-contract:" + UUID.randomUUID();
        RedisTransportDispatchHandoff producer = new RedisTransportDispatchHandoff(producerConnection, namespace, 2);
        RedisTransportDispatchHandoff consumer = new RedisTransportDispatchHandoff(consumerConnection, namespace, 2);
        StatefulRedisConnection<String, String> cleanupConnection = producerConnection;
        try {
            verifyContract(new HandoffFixture(producer, consumer, consumer));
        } finally {
            cleanupConnection.sync().keys(namespace + ":*").forEach(key -> cleanupConnection.sync().del(key));
            producer.shutdown();
            consumer.shutdown();
            redisClient.shutdown();
        }
    }

    private static void verifyContract(HandoffFixture fixture) throws Exception {
        assertEquals(
                List.of(DispatchOutcomeStatus.UNAVAILABLE),
                fixture.producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-no-consumer", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );

        claim(fixture.registry, DispatchMessageFixtures.mailboxKey(), "consumer-1");
        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED, DispatchOutcomeStatus.QUEUED),
                fixture.producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-1", "worker-1"),
                        DispatchMessageFixtures.item("msg-2", "worker-2")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
        assertEquals(
                List.of(DispatchOutcomeStatus.BACKPRESSURE),
                fixture.producer.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-3", "worker-3")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );

        List<DispatchMessage> firstBatch = fixture.consumer.poll(DispatchMessageFixtures.mailboxKey(), 10, 250L);
        assertEquals(List.of("msg-1", "msg-2"), DispatchMessageFixtures.messages(firstBatch));
        assertTrue(fixture.consumer.poll(DispatchMessageFixtures.mailboxKey(), 10, 0L).isEmpty());

        claim(fixture.registry, "mailbox-2", "consumer-2");
        fixture.producer.offer(new AdapterMailboxDispatchBatch(
                "mailbox-2",
                List.of(DispatchMessageFixtures.item("msg-4", "worker-4"))
        ));
        assertTrue(fixture.consumer.poll(DispatchMessageFixtures.mailboxKey(), 10, 0L).isEmpty());
        assertEquals(
                List.of("msg-4"),
                DispatchMessageFixtures.messages(fixture.consumer.poll("mailbox-2", 10, 250L))
        );
    }

    private static void claim(AdapterMailboxConsumerRegistry registry, String mailboxKey, String consumerId) {
        registry.publishMailboxConsumerAvailability(new AdapterMailboxConsumerAvailability(
                mailboxKey,
                consumerId,
                1L,
                System.currentTimeMillis() + 30_000L
        ));
    }

    private record HandoffFixture(TransportDispatchHandoff producer,
                                  TransportDispatchHandoff consumer,
                                  AdapterMailboxConsumerRegistry registry) {
    }
}
