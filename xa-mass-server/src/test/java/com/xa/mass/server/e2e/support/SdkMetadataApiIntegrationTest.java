package com.xa.mass.server.e2e.support;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class SdkMetadataApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkMetadataApisExposeSdkRegisteredProjectsAndEvents() {
        Map<String, Object> projectResponse = exchange("/api/v1/meta/projects", HttpMethod.GET, null);
        Map<String, Object> projectEventsResponse = exchange("/api/v1/meta/projects/demoApp/events", HttpMethod.GET, null);
        Map<String, Object> eventResponse = exchange("/api/v1/meta/events/crawler.fetch-page", HttpMethod.GET, null);
        Map<String, Object> capabilityResponse = exchange("/api/v1/meta/event-capabilities", HttpMethod.GET, null);

        assertApiOk(projectResponse);
        assertApiOk(projectEventsResponse);
        assertApiOk(eventResponse);
        assertApiOk(capabilityResponse);

        List<Map<String, Object>> projects = (List<Map<String, Object>>) projectResponse.get("data");
        List<Map<String, Object>> projectEvents = (List<Map<String, Object>>) projectEventsResponse.get("data");
        Map<String, Object> event = (Map<String, Object>) eventResponse.get("data");
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) capabilityResponse.get("data");

        assertTrue(projects.stream().anyMatch(project -> "demoApp".equals(project.get("code"))));
        assertTrue(projectEvents.stream().anyMatch(item -> "demo.dispatch".equals(item.get("code"))));
        assertTrue(projectEvents.stream().anyMatch(item -> "demo.dispatch.gb".equals(item.get("code"))));
        assertEquals("crawler.fetch-page", event.get("code"));
        assertEquals(List.of("JSON"), event.get("payloadTypes"));
        assertEquals(List.of("SINGLE_RUN", "STREAMING"), event.get("taskModes"));
        assertTrue(capabilities.stream().anyMatch(item ->
                "crawler.fetch-page".equals(item.get("eventCode"))
                        && "TASK_BACKED".equals(item.get("invocationModel"))));
        assertTrue(capabilities.stream().anyMatch(item ->
                "demo.dispatch".equals(item.get("eventCode"))
                        && List.of("demoApp", "otherApp", "testApp").equals(item.get("projectCodes"))));
        assertTrue(capabilities.stream().anyMatch(item ->
                "tool.country.capital.lookup".equals(item.get("eventCode"))
                        && "DIRECT_RUNTIME".equals(item.get("invocationModel"))
                        && Boolean.TRUE.equals(item.get("ready"))
                        && List.of().equals(item.get("projectCodes"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkMetadataApisAlsoExposeHandlerBackedUtilityEvents() {
        Map<String, Object> eventsResponse = exchange("/api/v1/meta/events", HttpMethod.GET, null);
        Map<String, Object> eventResponse = exchange("/api/v1/meta/events/tool.country.capital.lookup", HttpMethod.GET, null);

        assertApiOk(eventsResponse);
        assertApiOk(eventResponse);

        List<Map<String, Object>> events = (List<Map<String, Object>>) eventsResponse.get("data");
        Map<String, Object> event = (Map<String, Object>) eventResponse.get("data");

        assertTrue(events.stream().anyMatch(item -> "tool.time.now".equals(item.get("code"))));
        assertTrue(events.stream().anyMatch(item -> "tool.phone.country.detect".equals(item.get("code"))));
        assertEquals("tool.country.capital.lookup", event.get("code"));
        assertEquals(List.of("JSON"), event.get("payloadTypes"));
        assertEquals(List.of(), event.get("taskModes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlerBackedUtilityEventsDispatchRealData() {
        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("tool.country.capital.lookup")
                        .requestId("req-country-capital")
                        .payload(Map.of("countryCode", "GB"))
                        .build(),
                PrincipalContext.builder()
                        .principalId("metadata-test-client")
                        .userId("metadata-test-user")
                        .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                        .eventScopes(List.of("tool.country.capital.lookup"))
                        .build()
        );

        assertTrue(response.isSuccess());
        assertEquals("req-country-capital", response.getRequestId());

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals("GB", data.get("countryCode"));
        assertEquals("United Kingdom", data.get("countryName"));
        assertEquals("London", data.get("capital"));
    }
}
