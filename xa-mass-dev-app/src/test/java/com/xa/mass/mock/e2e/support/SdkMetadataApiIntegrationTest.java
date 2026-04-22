package com.xa.mass.mock.e2e.support;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class SdkMetadataApiIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkMetadataApisExposeDefaultProjectsAndEvents() {
        Map<String, Object> projectResponse = exchange("/sdk/meta/projects", HttpMethod.GET, null);
        Map<String, Object> projectEventsResponse = exchange("/sdk/meta/projects/demoApp/events", HttpMethod.GET, null);
        Map<String, Object> eventResponse = exchange("/sdk/meta/events/chatbot.reply", HttpMethod.GET, null);

        assertApiOk(projectResponse);
        assertApiOk(projectEventsResponse);
        assertApiOk(eventResponse);

        List<Map<String, Object>> projects = (List<Map<String, Object>>) projectResponse.get("data");
        List<Map<String, Object>> projectEvents = (List<Map<String, Object>>) projectEventsResponse.get("data");
        Map<String, Object> event = (Map<String, Object>) eventResponse.get("data");

        assertTrue(projects.stream().anyMatch(project -> "demoApp".equals(project.get("code"))));
        assertTrue(projectEvents.stream().anyMatch(item -> "crawler.fetch-page".equals(item.get("code"))));
        assertEquals("chatbot.reply", event.get("code"));
        assertEquals(List.of("TEXT", "JSON"), event.get("payloadTypes"));
        assertEquals(List.of("SINGLE_RUN", "STREAMING"), event.get("taskModes"));
    }
}
