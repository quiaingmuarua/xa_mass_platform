package com.xa.mass.server.e2e.bootstrap;

import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "mass.control-plane.seed.enabled=false"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
@Tag("secondary-proof")
public class CatalogRestoreWorkerCapabilityViewIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String JDBC_URL = sqliteUrl("catalog-capability");
    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        preseedCatalog();
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-sqlite", JDBC_URL, "", "");
    }

    @Test
    void restoredCatalogDrivesWorkerCapabilityReadViewWithoutCatalogOwningWorkerTopology() {
        assertTrue(app.listWorkerGroups().isEmpty(),
                "catalog restore must not persist or create WorkerGroup topology");

        app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId("capability-group")
                .eventBindings(List.of(WorkerEventBinding.builder()
                        .eventCode("durable.lookup")
                        .projectCodes(List.of("durableApp"))
                        .build()))
                .build());
        app.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId("capability-node-001")
                .adapterType("polling")
                .endpointId("polling://capability-node-001")
                .build());
        app.bindNodeGroup(NodeGroupBindingRegistration.builder()
                .adapterNodeId("capability-node-001")
                .workerGroupId("capability-group")
                .build());
        app.registerWorker(WorkerRegistration.builder()
                .workerId("capability-worker-001")
                .adapterNodeId("capability-node-001")
                .workerGroupId("capability-group")
                .adapterId("polling")
                .transportHint("polling")
                .maxConcurrentWork(1)
                .build());
        app.reportWorkerCapability(new WorkerCapabilityReportRequest(
                "capability-worker-001",
                1,
                List.of("durable.lookup"),
                Map.of(),
                "test-agent"
        ));

        Map<String, Object> response = exchange("/api/v1/runtime/workers", HttpMethod.GET, null);
        assertApiOk(response);

        Map<String, Object> worker = apiItems(response).stream()
                .filter(item -> "capability-worker-001".equals(item.get("workerId")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> eventBindings = listOfMaps(worker.get("eventBindings"));
        Map<String, Object> binding = eventBindings.stream()
                .filter(item -> "durable.lookup".equals(item.get("eventCode")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("durableApp"), binding.get("projectCodes"));
    }

    static void preseedCatalog() {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                JdbcStorageMode.JDBC_SQLITE,
                JDBC_URL,
                "",
                "")) {
            runtime.catalogMetadataStore().upsertCatalog(
                    List.of(CatalogMetadataSQLiteRestartIntegrationTest.event("durable.lookup", "durableApp")),
                    List.of(CatalogMetadataSQLiteRestartIntegrationTest.project("durableApp", "durable.lookup"))
            );
        }
    }

    private static String sqliteUrl(String prefix) {
        try {
            Path db = Files.createTempDirectory("xa-mass-" + prefix).resolve("xa_mass.db");
            return "jdbc:sqlite:" + db;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite test database", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> apiItems(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (data instanceof Map<?, ?> map) {
            Object items = map.get("items");
            return items instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
