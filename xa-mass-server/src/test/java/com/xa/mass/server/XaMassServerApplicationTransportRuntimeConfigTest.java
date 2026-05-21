package com.xa.mass.server;

import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.presence.RedisWorkerPresenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class XaMassServerApplicationTransportRuntimeConfigTest {

    @Test
    void presenceStoreDefaultsToInMemorySdkStore() {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportPresenceStore", "memory");

        Supplier<WorkerPresenceStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportPresenceStoreFactory");

        assertThat(factory).isNull();
    }

    @Test
    void redisPresenceStoreCanBeSelectedForServerStartup() throws Exception {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportPresenceStore", "redis");
        ReflectionTestUtils.setField(application, "transportPresenceRedisNamespace", "xa:mass:test:server-presence");
        ReflectionTestUtils.setField(application, "transportPresenceLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "transportNodeId", "server-node-a");
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<WorkerPresenceStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportPresenceStoreFactory");

        assertThat(factory).isNotNull();
        WorkerPresenceStore store = factory.get();
        try {
            assertThat(store).isInstanceOf(RedisWorkerPresenceStore.class);
        } finally {
            if (store instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
