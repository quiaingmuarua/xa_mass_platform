package com.xa.mass.server.e2e.bootstrap;

import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.api.CatalogProjectRecord;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
public class CatalogMetadataSQLiteRestartIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String JDBC_URL = sqliteUrl("catalog-restart");
    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        preseedCatalog();
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-sqlite", JDBC_URL, "", "");
    }

    @Test
    void sqliteCatalogMetadataRestoresWithoutSeedImportOrDefaultFallback() {
        assertNotNull(app.getProject("durableApp"));
        assertNull(app.getProject("demoApp"), "default demo catalog must not satisfy durable restore proof");

        EventDefinition event = app.getEvent("durable.lookup");
        assertNotNull(event);
        assertEquals(List.of("durableApp"), event.getProjectCodes());

        Map<String, Object> projects = exchange("/api/v1/projects", HttpMethod.GET, null);
        assertApiOk(projects);
        assertTrue(apiDataList(projects).stream()
                .anyMatch(item -> "durableApp".equals(item.get("code"))));
        assertFalse(apiDataList(projects).stream()
                .anyMatch(item -> "demoApp".equals(item.get("code"))));

        Map<String, Object> events = exchange("/api/v1/catalog/events", HttpMethod.GET, null);
        assertApiOk(events);
        assertTrue(apiDataList(events).stream()
                .anyMatch(item -> "durable.lookup".equals(item.get("code"))));
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
                    List.of(event("durable.lookup", "durableApp")),
                    List.of(project("durableApp", "durable.lookup"))
            );
        }
    }

    static CatalogEventRecord event(String code, String... projectCodes) {
        return new CatalogEventRecord(
                code,
                "Durable Lookup",
                "Durable catalog event",
                List.of("JSON"),
                List.of("SINGLE_RUN"),
                true,
                null,
                List.of(projectCodes),
                "STANDARD",
                "FINAL_RESULT",
                "NONE",
                "FINAL_RESULT",
                "WORKER"
        );
    }

    static CatalogProjectRecord project(String code, String... eventCodes) {
        return new CatalogProjectRecord(
                "default",
                code,
                "Durable App",
                "Durable catalog project",
                true,
                null,
                List.of(eventCodes)
        );
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
    private static List<Map<String, Object>> apiDataList(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
