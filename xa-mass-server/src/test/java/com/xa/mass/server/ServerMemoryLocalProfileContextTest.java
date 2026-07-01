package com.xa.mass.server;

import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.task.runtime.starter.TaskRuntimeBackendKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.engine.assignment-retry-delay-millis=100",
                "mass.engine.runtime-ready-dispatch-idle-backoff-max-millis=500"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServerMemoryLocalProfileContextTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String H2_URL = isolatedH2JdbcUrl("memory_local_profile_context");

    @Autowired
    private JdbcStorageRuntime jdbcStorageRuntime;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-h2", H2_URL, "sa", "");
    }

    @Test
    void memoryLocalStartsWithH2ControlPlaneAndMemoryRuntime() {
        assertThat(jdbcStorageRuntime.isEnabled()).isTrue();
        assertThat(applicationContext.containsBean("taskWorkRuntime")).isFalse();
        assertThat(applicationContext.containsBean("taskResultRuntime")).isFalse();
        assertThat(runtimeEngineConfig().getTaskRuntimeBootstrapConfig().backendKind())
                .isEqualTo(TaskRuntimeBackendKind.MEMORY);
    }

    private EngineConfig runtimeEngineConfig() {
        Object delegate = ReflectionTestUtils.getField(app, "delegate");
        Object engine = ReflectionTestUtils.getField(delegate, "engine");
        return (EngineConfig) ReflectionTestUtils.getField(engine, "config");
    }
}
