package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ReviewReadModelSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=true",
                "mass.mock.bootstrap.register-dev-catalog=false",
                "mass.mock.bootstrap.register-dev-api-keys=false",
                "mass.mock.bootstrap.load-rules=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.control-plane.seed.enabled=true",
                "mass.control-plane.seed.catalog-location=file:../integrations/samples/dev/scenario/bootstrap.json",
                "mass.control-plane.seed.rules-location=file:../integrations/samples/dev/scenario/rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
@Tag("secondary-proof")
class DevSampleWorkerLauncherIntegrationTest extends ReviewReadModelSampleE2eTest {

    /**
     * Support-only dev launcher coverage.
     *
     * <p>This validates sample bootstrap wiring and external sample flows. It is
     * useful for local confidence, but it is not proof ownership for scheduling,
     * lifecycle, or external-worker parity.
     */

    private static final int WAIT_ATTEMPTS = 80;
    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String STOCK_WORKER_ID = "stock-ws-worker-001";
    private static final String CRAWLER_WORKER_ID = "node-worker-realtime-001";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void devAppLauncherAutoRegistersAndStartsSampleWorkers() throws Exception {
        waitForWorkerOnline(CRAWLER_WORKER_ID);
        waitForWorkerOnline(STOCK_WORKER_ID);
        WorkerSnapshot stockWorker = app.getAllWorkers().stream()
                .filter(worker -> STOCK_WORKER_ID.equals(worker.getWorkerId()))
                .findFirst()
                .orElse(null);
        assertNotNull(stockWorker);
        assertTrue(stockWorker.getAttributes().containsKey("routingTags"));
        assertTrue(app.getProject("crawlerApp") != null);
        assertTrue(app.getEvent("stock.quote.fetch") != null);
        List<String> ruleIds = app.listDefaultRules().stream()
                .map(rule -> String.valueOf(rule.get("ruleId")))
                .sorted()
                .toList();
        assertEquals(List.of(
                "basic_worker_check",
                "routing_country_match",
                "target_worker_attributes_check"
        ), ruleIds);
        waitForSeedTaskContaining("sample-crawler-fetch-page", "TERMINAL");
        waitForSeedTaskContaining("sample-stock-quote-stream", "RUNNING");

        String requestId = "launcher-stock-req-0001";
        String sourceUrl = "http://127.0.0.1:" + port + "/api/v1/catalog/events/stock.quote.fetch";
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "launcher-itest");
        createBody.put("sourceRef", "launcher-stock-quote");
        createBody.put("sharedConfig", Map.of(
                "routingCode", "us",
                "sourceUrl", sourceUrl
        ));
        createBody.put("executionSpec", Map.of("batchSize", 1));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "stock.quote.fetch", List.of(Map.of(
                "requestId", requestId,
                "symbol", "NVDA",
                "market", "NASDAQ"
        ))));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));

        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        Map<String, Object> message = terminal.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertEquals(STOCK_WORKER_ID, message.get("latestAttemptWorkerId"));

        Object outputObject = message.get("output");
        assertInstanceOf(Map.class, outputObject);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) outputObject;
        assertEquals(requestId, output.get("requestId"));
        assertEquals("NVDA", output.get("symbol"));
        assertEquals("NASDAQ", output.get("market"));
        assertEquals("USD", output.get("currency"));
        assertEquals(sourceUrl, output.get("source"));
        assertTrue(output.get("price") instanceof Number);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerProfile = (Map<String, Object>) output.get("workerProfile");
        assertEquals("node-websocket-worker", workerProfile.get("runtime"));
        assertEquals(STOCK_WORKER_ID, workerProfile.get("workerId"));
    }

    private void waitForWorkerOnline(String workerId) throws InterruptedException {
        WorkerSnapshot latestWorker = null;
        for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
            latestWorker = app.getAllWorkers().stream()
                    .filter(worker -> workerId.equals(worker.getWorkerId()))
                    .findFirst()
                    .orElse(null);
            if (latestWorker != null && app.isWorkerOnline(workerId)) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Worker did not reach transport-online state: " + workerId + ", lastWorker=" + latestWorker);
    }

    @SuppressWarnings("unchecked")
    private void waitForSeedTaskContaining(String taskNameFragment, String expectedStatus) throws InterruptedException {
        Map<String, Object> matched = null;
        for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
            Map<String, Object> response = exchange("/api/v1/tasks", HttpMethod.GET, null);
            assertApiOk(response);
            List<Map<String, Object>> items = (List<Map<String, Object>>) responseData(response).get("items");
            matched = items.stream()
                    .filter(item -> String.valueOf(item.get("taskName")).contains(taskNameFragment))
                    .findFirst()
                    .orElse(null);
            if (matched != null && Objects.equals(expectedStatus, matched.get("status"))) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Seed task did not reach status " + expectedStatus + ": " + taskNameFragment
                + ", lastTask=" + matched);
    }
}
