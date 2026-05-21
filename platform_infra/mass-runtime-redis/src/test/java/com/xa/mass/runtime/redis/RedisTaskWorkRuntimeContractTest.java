package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.contract.TaskWorkRuntimeContractTest;
import io.lettuce.core.RedisClient;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

class RedisTaskWorkRuntimeContractTest extends TaskWorkRuntimeContractTest {

    private RedisClient redisClient;
    private String redisUri;
    private String namespace;

    @Override
    protected TaskWorkRuntime createRuntime(AtomicReference<Instant> clock) {
        redisUri = RedisRuntimeTestSupport.redisUri();
        namespace = RedisRuntimeTestSupport.namespace("work-contract");
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("work runtime contract test");
        return new RedisTaskWorkRuntime(
                redisClient,
                namespace,
                1024,
                clock::get,
                true
        );
    }

    @Override
    protected void destroyRuntime(TaskWorkRuntime runtime) {
        try {
            runtime.shutdown();
        } finally {
            RedisRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            redisClient = null;
            namespace = null;
        }
    }
}
