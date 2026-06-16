package com.xa.mass.transport.runtime.lease;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

class RedisTransportEndpointLeaseStoreContractTest extends TransportEndpointLeaseStoreContractTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for endpoint lease contract test: "
                    + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-endpoint-lease-contract:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (observerCommands != null) {
            for (String key : observerCommands.keys(namespacePrefix + ":*")) {
                observerCommands.del(key);
            }
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Override
    protected LeaseStoreFixture createFixture(long leaseMillis) {
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        RedisTransportEndpointLeaseStore store =
                new RedisTransportEndpointLeaseStore(connection, namespacePrefix, leaseMillis, "runtime-a");
        return new LeaseStoreFixture(store, store, store);
    }
}
