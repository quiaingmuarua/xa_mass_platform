package com.xa.mass.server;

import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    @Test
    void prodProfileRejectsMemoryRuntimeMode() {
        XaMassServerApplication application = prodApplication();
        ReflectionTestUtils.setField(application, "runtimeMode", "memory");

        assertThatThrownBy(application::taskWorkRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod requires mass.runtime.mode=redis");
        assertThatThrownBy(application::taskResultRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod requires mass.runtime.mode=redis");
    }

    @Test
    void prodProfileRejectsDisabledStorageMode() {
        XaMassServerApplication application = prodApplication();
        ReflectionTestUtils.setField(application, "storageMode", "memory");

        assertThatThrownBy(application::jdbcStorageRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod requires mass.storage.mode to be JDBC-enabled");
    }

    @Test
    void prodProfileRejectsMemoryTransportStores() {
        XaMassServerApplication application = prodApplication();
        ReflectionTestUtils.setField(application, "transportDeliveryStore", "memory");
        ReflectionTestUtils.setField(application, "transportPresenceStore", "memory");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod requires mass.transport.delivery.store=redis");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportPresenceStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod requires mass.transport.presence.store=redis");
    }

    @Test
    void prodProfileAcceptsRedisTransportModesWithoutInstantiatingRedis() {
        XaMassServerApplication application = prodApplication();
        ReflectionTestUtils.setField(application, "transportDeliveryStore", "redis");
        ReflectionTestUtils.setField(application, "transportPresenceStore", "redis");
        ReflectionTestUtils.setField(application, "transportDeliveryRedisNamespace", "xa:mass:test:server-delivery");
        ReflectionTestUtils.setField(application, "transportPresenceRedisNamespace", "xa:mass:test:server-presence");
        ReflectionTestUtils.setField(application, "transportDeliveryMaxQueuedItems", 100);
        ReflectionTestUtils.setField(application, "transportDeliveryMaxItemsPerRoute", 10);
        ReflectionTestUtils.setField(application, "transportPresenceLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "transportNodeId", "server-node-a");
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<TransportDeliveryStore> deliveryFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory");
        Supplier<WorkerPresenceStore> presenceFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportPresenceStoreFactory");

        assertThat(deliveryFactory).isNotNull();
        assertThat(presenceFactory).isNotNull();
    }

    private XaMassServerApplication prodApplication() {
        XaMassServerApplication application = new XaMassServerApplication();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ReflectionTestUtils.setField(application, "environment", environment);
        return application;
    }
}
