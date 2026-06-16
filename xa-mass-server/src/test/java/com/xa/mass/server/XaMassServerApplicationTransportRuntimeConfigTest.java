package com.xa.mass.server;

import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XaMassServerApplicationTransportRuntimeConfigTest {

    @Test
    void endpointLeaseStoreDefaultsToInMemorySdkStore() {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "memory");

        Supplier<TransportEndpointLeaseStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportEndpointLeaseStoreFactory");

        assertThat(factory).isNull();
    }

    @Test
    void redisEndpointLeaseStoreCanBeSelectedForServerStartup() throws Exception {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "redis");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseRedisNamespace", "xa:mass:test:server-endpoint-lease");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<TransportEndpointLeaseStore> factory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportEndpointLeaseStoreFactory");

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
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "memory");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.delivery.store=redis");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(application, "resolveTransportEndpointLeaseStoreFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.endpoint-lease.store=redis");
    }

    @Test
    void durableLocalProfileAcceptsRedisTransportModesWithoutInstantiatingRedis() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "transportDeliveryStore", "redis");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "redis");
        ReflectionTestUtils.setField(application, "transportDeliveryRedisNamespace", "xa:mass:test:server-delivery");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseRedisNamespace", "xa:mass:test:server-endpoint-lease");
        ReflectionTestUtils.setField(application, "transportDeliveryMaxQueuedItems", 100);
        ReflectionTestUtils.setField(application, "transportDeliveryMaxItemsPerRoute", 10);
        ReflectionTestUtils.setField(application, "transportEndpointLeaseMillis", 1234L);
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");

        Supplier<TransportDeliveryStore> deliveryFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportDeliveryStoreFactory");
        Supplier<TransportEndpointLeaseStore> endpointLeaseFactory =
                ReflectionTestUtils.invokeMethod(application, "resolveTransportEndpointLeaseStoreFactory");

        assertThat(deliveryFactory).isNotNull();
        assertThat(endpointLeaseFactory).isNotNull();
    }

    private XaMassServerApplication durableLocalApplication() {
        XaMassServerApplication application = new XaMassServerApplication();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("durable-local");
        ReflectionTestUtils.setField(application, "environment", environment);
        return application;
    }
}
