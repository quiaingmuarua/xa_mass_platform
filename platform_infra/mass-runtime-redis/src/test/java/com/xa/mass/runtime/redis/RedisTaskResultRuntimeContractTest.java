package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.contract.TaskResultRuntimeContractTest;
import org.junit.jupiter.api.Assumptions;

import java.util.UUID;

class RedisTaskResultRuntimeContractTest extends TaskResultRuntimeContractTest {

    @Override
    protected TaskResultRuntime createRuntime() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            return new RedisTaskResultRuntime(
                    io.lettuce.core.RedisClient.create(redisUri),
                    "xa:mass:test:result:" + UUID.randomUUID(),
                    java.time.Instant::now,
                    true,
                    25L
            );
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for contract test: " + ex.getMessage());
            throw ex;
        }
    }
}
