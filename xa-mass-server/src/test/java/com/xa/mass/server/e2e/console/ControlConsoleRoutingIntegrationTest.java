package com.xa.mass.server.e2e.console;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class ControlConsoleRoutingIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String TEST_DIST_PATH = resolveTestFrontendDistPath();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.frontend.dist-path", () -> TEST_DIST_PATH);
    }

    @Test
    void backendHostedConsoleRoutesServeTheBuiltShell() throws Exception {
        HttpResponse<String> root = get("/");
        HttpResponse<String> tasks = get("/tasks");
        HttpResponse<String> workers = get("/resources/workers");
        HttpResponse<String> workerDetail = get("/resources/workers/worker-001");

        assertEquals(200, root.statusCode());
        assertEquals(200, tasks.statusCode());
        assertEquals(200, workers.statusCode());
        assertEquals(200, workerDetail.statusCode());
        assertTrue(root.body().contains("integration-console-shell"));
        assertTrue(tasks.body().contains("<div id=\"app\"></div>"));
        assertTrue(workers.body().contains("<div id=\"app\"></div>"));
        assertTrue(workerDetail.body().contains("<div id=\"app\"></div>"));
    }

    @Test
    void legacyConsoleAliasesRedirectToPrimarySpaRoutes() throws Exception {
        assertRedirect("/status", "/");
        assertRedirect("/status/tasks", "/tasks");
        assertRedirect("/status/workers", "/resources/workers");
        assertRedirect("/status/rules", "/resources/rules");
        assertRedirect("/config", "/resources/configs");
    }

    private void assertRedirect(String path, String expectedLocation) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(302, response.statusCode(), "unexpected redirect status for " + path);
        assertEquals(expectedLocation, response.headers().firstValue("location").orElse(""), "unexpected redirect target for " + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String resolveTestFrontendDistPath() {
        for (Path candidate : new Path[]{
                Paths.get("src", "test", "resources", "frontend-dist"),
                Paths.get("xa-mass-server", "src", "test", "resources", "frontend-dist")
        }) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute.resolve("index.html"))) {
                return absolute.toString();
            }
        }
        throw new IllegalStateException("Unable to resolve xa-mass-server test frontend-dist path");
    }
}
