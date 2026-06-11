package com.xa.mass.server;

import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XaMassServerApplicationTransportRuntimeConfigTest {

    @Test
    void routeOwnerStoreDefaultsToInMemorySdkStore() {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportRouteOwnerStore", "memory");

        Supplier<TransportRouteOwnerStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportRouteOwnerStoreFactory");

        assertThat(factory).isNull();
    }

    @Test
    void redisRouteOwnerStoreCanBeSelectedForServerStartup() throws Exception {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportRouteOwnerStore", "redis");
        ReflectionTestUtils.setField(application, "transportRouteOwnerRedisNamespace", "xa:mass:test:server-route-owner");
        ReflectionTestUtils.setField(application, "transportRouteOwnerLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "transportNodeId", "server-node-a");
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<TransportRouteOwnerStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportRouteOwnerStoreFactory");

        assertThat(factory).isNotNull();
    }

    @Test
    void durableLocalProfileRejectsMemoryRuntimeMode() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "runtimeMode", "memory");

        assertThatThrownBy(application::taskWorkRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.runtime.mode=redis");
        assertThatThrownBy(application::taskResultRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.runtime.mode=redis");
    }

    @Test
    void durableLocalProfileRejectsDisabledStorageMode() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "storageMode", "memory");

        assertThatThrownBy(application::jdbcStorageRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.storage.mode to be JDBC-enabled");
    }

    @Test
    void durableLocalProfileRejectsMemoryTransportStores() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "transportDeliveryStore", "memory");
        ReflectionTestUtils.setField(application, "transportRouteOwnerStore", "memory");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.delivery.store=redis");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportRouteOwnerStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.route-owner.store=redis");
    }

    @Test
    void durableLocalProfileAcceptsRedisTransportModesWithoutInstantiatingRedis() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "transportDeliveryStore", "redis");
        ReflectionTestUtils.setField(application, "transportRouteOwnerStore", "redis");
        ReflectionTestUtils.setField(application, "transportDeliveryRedisNamespace", "xa:mass:test:server-delivery");
        ReflectionTestUtils.setField(application, "transportRouteOwnerRedisNamespace", "xa:mass:test:server-route-owner");
        ReflectionTestUtils.setField(application, "transportDeliveryMaxQueuedItems", 100);
        ReflectionTestUtils.setField(application, "transportDeliveryMaxItemsPerRoute", 10);
        ReflectionTestUtils.setField(application, "transportRouteOwnerLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "transportNodeId", "server-node-a");
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<TransportDeliveryStore> deliveryFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory");
        Supplier<TransportRouteOwnerStore> routeOwnerFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportRouteOwnerStoreFactory");

        assertThat(deliveryFactory).isNotNull();
        assertThat(routeOwnerFactory).isNotNull();
    }

    private XaMassServerApplication durableLocalApplication() {
        XaMassServerApplication application = new XaMassServerApplication();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("durable-local");
        ReflectionTestUtils.setField(application, "environment", environment);
        return application;
    }
}
