package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.socket.enabled=true"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
@Tag("secondary-proof")
class ExternalWorkerRealtimeRegistrationIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "realtime-worker-websocket";
    private static final String WORKER_KEY = "realtime-worker-websocket-key";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.socket.port", () -> 0);
    }

    @Test
    void registerWorkerUsesRealtimeTransportHintWithoutAdapterNodeId() {
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("realtime-worker-websocket-principal")
                .credential(WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", WORKER_ID))
                .build());
        HttpHeaders workerHeaders = credentialHeaders(WORKER_KEY);
        declareExternalWorkerGroup("realtime-crawler", "crawlerApp", "crawler.fetch-page", workerHeaders);
        assertApiOk(exchange("/worker-api/v1/adapter-nodes", HttpMethod.POST, Map.of(
                "adapterNodeId", "realtime-node",
                "adapterType", "websocket",
                "endpointId", "realtime-node"
        ), workerHeaders));
        assertApiOk(exchange("/worker-api/v1/node-group-bindings", HttpMethod.POST, Map.of(
                "adapterNodeId", "realtime-node",
                "workerGroupId", "realtime-crawler"
        ), workerHeaders));

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", WORKER_ID,
                "workerGroupId", "realtime-crawler",
                "transportHint", "realtime"
        ), workerHeaders);

        assertApiOk(registerResponse);
        assertEquals("realtime", responseData(registerResponse).get("transportHint"));
        assertTrue(!responseData(registerResponse).containsKey("adapterId"));
        assertEquals("realtime", app.getWorkerTransportHint(WORKER_ID));
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }
}
