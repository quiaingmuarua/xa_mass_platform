package com.xa.mass.server;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.storage.memory.InMemoryCatalogMetadataStore;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.task.runtime.starter.TaskRuntimeBackendKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class XaMassServerApplicationTransportRuntimeConfigTest {

    @Test
    void serverAssemblyRoutesMemoryRuntimeModeToMemoryTaskRuntimeBackend() {
        XaMassServerApplication application = baselineApplication();
        ReflectionTestUtils.setField(application, "runtimeMode", "memory");

        MassSdkApplication app = buildApplication(application);
        EngineConfig config = engineConfig(app);

        assertThat(config.getTaskRuntimeBootstrapConfig().backendKind())
                .isEqualTo(TaskRuntimeBackendKind.MEMORY);
    }

    @Test
    void serverAssemblyRoutesRedisRuntimeModeToRedisTaskRuntimeBackend() {
        XaMassServerApplication application = redisRuntimeApplication();
        ReflectionTestUtils.setField(application, "runtimeRedisNamespace", "xa:mass:test:runtime");

        MassSdkApplication app = buildApplication(application);
        EngineConfig config = engineConfig(app);

        assertThat(config.getTaskRuntimeBootstrapConfig().backendKind())
                .isEqualTo(TaskRuntimeBackendKind.REDIS);
        assertThat(config.getTaskRuntimeBootstrapConfig().redisNamespace())
                .isEqualTo("xa:mass:test:runtime:task-runtime");
    }

    @Test
    void durableLocalProfileRejectsMemoryRuntimeMode() {
        XaMassServerApplication application = durableLocalApplication();
        ReflectionTestUtils.setField(application, "runtimeMode", "memory");
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryBufferStore", "redis");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "redis");

        assertThatThrownBy(() -> buildApplication(application))
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
    void durableLocalProfileRejectsMemoryPollingDeliveryStore() {
        XaMassServerApplication application = durableLocalApplication();
        configureRedisRuntime(application);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryBufferStore", "memory");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "redis");

        assertThatThrownBy(() -> buildApplication(application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.polling.buffer.store=redis");
    }

    @Test
    void durableLocalProfileRejectsMemoryEndpointLeaseStore() {
        XaMassServerApplication application = durableLocalApplication();
        configureRedisRuntime(application);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryBufferStore", "redis");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "memory");

        assertThatThrownBy(() -> buildApplication(application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local requires mass.transport.endpoint-lease.store=redis");
    }

    @Test
    void durableLocalProfileAcceptsRedisTransportModesWithoutInstantiatingRedis() {
        XaMassServerApplication application = durableLocalApplication();
        configureRedisRuntime(application);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryBufferStore", "redis");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "redis");
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryRedisNamespace",
                "xa:mass:test:server-polling-delivery");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseRedisNamespace",
                "xa:mass:test:server-endpoint-lease");

        MassSdkApplication app = buildApplication(application);
        TransportConfig transportConfig = transportConfig(app);

        assertThat(transportConfig.getBackendDeclaration().pollingDeliveryNamespace())
                .isEqualTo("xa:mass:test:server-polling-delivery");
        assertThat(transportConfig.getBackendDeclaration().endpointLeaseNamespace())
                .isEqualTo("xa:mass:test:server-endpoint-lease");
    }

    private static MassSdkApplication buildApplication(XaMassServerApplication application) {
        return application.fullStackRuntimeApplication(
                JdbcStorageRuntime.disabled(),
                new InMemoryCatalogMetadataStore(),
                new InMemoryTaskShellStore(),
                mock(ObjectProvider.class));
    }

    private static EngineConfig engineConfig(MassSdkApplication app) {
        MassApplication delegate = (MassApplication) ReflectionTestUtils.getField(app, "delegate");
        MassEngine engine = (MassEngine) ReflectionTestUtils.getField(delegate, "engine");
        return (EngineConfig) ReflectionTestUtils.getField(engine, "config");
    }

    private static TransportConfig transportConfig(MassSdkApplication app) {
        MassApplication delegate = (MassApplication) ReflectionTestUtils.getField(app, "delegate");
        return (TransportConfig) ReflectionTestUtils.getField(delegate, "transportConfig");
    }

    private static XaMassServerApplication redisRuntimeApplication() {
        XaMassServerApplication application = baselineApplication();
        configureRedisRuntime(application);
        return application;
    }

    private static XaMassServerApplication baselineApplication() {
        XaMassServerApplication application = new XaMassServerApplication();
        ReflectionTestUtils.setField(application, "transportRuntimeMaxPendingTasks", 100);
        ReflectionTestUtils.setField(application, "eventRuntimeMaxPendingTasks", 100);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryMaxQueuedItems", 100);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryMaxItemsPerWorker", 10);
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryBufferStore", "memory");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseStore", "memory");
        ReflectionTestUtils.setField(application, "transportPollingPendingDeliveryRedisNamespace",
                "xa:mass:test:polling-delivery");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseRedisNamespace",
                "xa:mass:test:endpoint-lease");
        ReflectionTestUtils.setField(application, "transportEndpointLeaseMillis", 30_000L);
        ReflectionTestUtils.setField(application, "runtimeRedisNamespace", "xa:mass:test:runtime");
        return application;
    }

    private static void configureRedisRuntime(XaMassServerApplication application) {
        ReflectionTestUtils.setField(application, "runtimeMode", "redis");
        ReflectionTestUtils.setField(application, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(application, "redisPort", 6379);
        ReflectionTestUtils.setField(application, "redisDatabase", 0);
        ReflectionTestUtils.setField(application, "redisPassword", "");
    }

    private static XaMassServerApplication durableLocalApplication() {
        XaMassServerApplication application = baselineApplication();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("durable-local");
        ReflectionTestUtils.setField(application, "environment", environment);
        return application;
    }
}
