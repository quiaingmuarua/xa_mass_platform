package com.xa.mass.server.e2e.bootstrap;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.bootstrap.enabled=false"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
@Tag("secondary-proof")
class CleanServerStartupIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void devStartupBootstrapsMetadataButDoesNotCreateScenarioWorkload() {
        assertNotNull(app);
        assertNotNull(app.getProject("crawlerApp"));
        assertNotNull(app.getEvent("crawler.fetch-page"));

        assertTrue(app.listTaskSummaries(0, 10).isEmpty(),
                "clean dev startup must not create task shells");
        assertTrue(app.getAllWorkers().isEmpty(),
                "clean dev startup must not register workers");
        assertTrue(app.listWorkerGroups().isEmpty(),
                "clean dev startup must not declare WorkerGroups");
    }
}
