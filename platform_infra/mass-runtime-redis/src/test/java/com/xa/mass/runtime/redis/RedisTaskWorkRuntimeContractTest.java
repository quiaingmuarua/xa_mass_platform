package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.contract.TaskWorkRuntimeContractTest;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Assumptions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class RedisTaskWorkRuntimeContractTest extends TaskWorkRuntimeContractTest {

    private RedisClient redisClient;

    @Override
    protected TaskWorkRuntime createRuntime(AtomicReference<Instant> clock) {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            return new RedisTaskWorkRuntime(
                    redisClient,
                    "xa:mass:test:" + UUID.randomUUID(),
                    1024,
                    clock::get,
                    true
            );
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for contract test: " + ex.getMessage());
            throw ex;
        }
    }

    @Override
    protected void destroyRuntime(TaskWorkRuntime runtime) {
        try {
            runtime.shutdown();
        } finally {
            redisClient = null;
        }
    }
}
