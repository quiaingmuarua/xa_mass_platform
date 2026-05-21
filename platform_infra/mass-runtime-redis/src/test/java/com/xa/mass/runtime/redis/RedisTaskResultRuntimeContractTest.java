package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.contract.TaskResultRuntimeContractTest;

class RedisTaskResultRuntimeContractTest extends TaskResultRuntimeContractTest {

    private String redisUri;
    private String namespace;

    @Override
    protected TaskResultRuntime createRuntime() {
        redisUri = RedisRuntimeTestSupport.redisUri();
        namespace = RedisRuntimeTestSupport.namespace("result-contract");
        return new RedisTaskResultRuntime(
                RedisRuntimeTestSupport.createClientOrSkip("result runtime contract test"),
                namespace,
                java.time.Instant::now,
                true,
                25L
        );
    }

    @Override
    protected void destroyRuntime(TaskResultRuntime runtime) {
        if (runtime == null) {
            namespace = null;
            return;
        }
        try {
            super.destroyRuntime(runtime);
        } finally {
            RedisRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            namespace = null;
        }
    }
}
