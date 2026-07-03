package com.xa.mass.server;

import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-h2", H2_URL, "sa", "");
    }

    @Test
    void memoryLocalStartsWithH2ControlPlaneAndMemoryRuntime() {
        assertThat(jdbcStorageRuntime.isEnabled()).isTrue();
        assertThat(taskWorkRuntime).isInstanceOf(InMemoryTaskWorkRuntime.class);
    }
}
