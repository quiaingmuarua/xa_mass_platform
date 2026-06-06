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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.bootstrap.enabled=false",
                "mass.mock.bootstrap.register-dev-catalog=false",
                "mass.mock.bootstrap.register-dev-api-keys=false",
                "mass.mock.bootstrap.load-rules=false",
                "mass.control-plane.seed.enabled=true",
                "mass.control-plane.seed.catalog-location=file:../integrations/samples/dev/scenario/bootstrap.json",
                "mass.control-plane.seed.rules-location=file:../integrations/samples/dev/scenario/rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
@Tag("secondary-proof")
public class ControlPlaneSeedImportIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void explicitSeedImportCreatesControlPlaneMetadataOnly() {
        assertNotNull(app.getProject("crawlerApp"));
        assertNotNull(app.getEvent("stock.quote.fetch"));
        assertNotNull(app.getCredentialPrincipal("crawler-task-api-key"));
        assertNotNull(app.getCredentialPrincipal("phone-device-probe-poll-sg-001"));
        assertEquals("fp-sg-alpha",
                app.getCredentialPrincipal("phone-device-probe-poll-sg-001").getAttributes().get("fingerprintProfile"));

        List<String> ruleIds = app.listDefaultRules().stream()
                .map(rule -> String.valueOf(rule.get("ruleId")))
                .sorted()
                .toList();
        assertEquals(List.of(
                "basic_worker_check",
                "routing_country_match",
                "target_worker_attributes_check"
        ), ruleIds);

        assertTrue(app.listTaskSummaries(0, 10).isEmpty(),
                "seed/import must not create task shells");
        assertTrue(app.getAllWorkers().isEmpty(),
                "seed/import must not register workers");
        assertTrue(app.listWorkerGroups().isEmpty(),
                "seed/import must not declare WorkerGroups");
    }
}
