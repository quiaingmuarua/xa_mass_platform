package com.xa.mass.api.internal;

import com.xa.mass.api.console.FrontendConsoleRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendConsoleControllerTest {

    @TempDir
    Path tempDir;

    private FrontendConsoleRoutingService routingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        routingService = new FrontendConsoleRoutingService();
    }

    @Test
    void legacyStatusPageRedirectsToBackendConsoleRouteWhenLocalBuildMissing() throws Exception {
        configureRoutingService(tempDir.resolve("missing-dist"));
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        mockMvc.perform(get("/status/workers"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/resources/workers"));
    }

    @Test
    void consoleRouteServesLocalIndexWhenBuildExists() throws Exception {
        Path distDir = Files.createDirectories(tempDir.resolve("frontend-dist"));
        Files.writeString(distDir.resolve("index.html"), "<!doctype html><html><body>console-shell</body></html>");
        configureRoutingService(distDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        mockMvc.perform(get("/tasks/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("console-shell")));

        mockMvc.perform(get("/tasks/task-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("console-shell")));

        mockMvc.perform(get("/tasks/task-001/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("console-shell")));
    }

    @Test
    void nestedWorkerConsoleRouteServesLocalIndexWhenBuildExists() throws Exception {
        Path distDir = Files.createDirectories(tempDir.resolve("frontend-dist-worker"));
        Files.writeString(distDir.resolve("index.html"), "<!doctype html><html><body>worker-console-shell</body></html>");
        configureRoutingService(distDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        mockMvc.perform(get("/resources/workers/worker-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("worker-console-shell")));

        mockMvc.perform(get("/resources/workers/worker-001/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("worker-console-shell")));
    }

    @Test
    void currentConsoleRoutesServeLocalIndexWhenBuildExists() throws Exception {
        Path distDir = createLocalDist();
        configureRoutingService(distDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        for (String path : java.util.List.of(
                "/resources/projects",
                "/resources/projects/demoApp",
                "/resources/projects/demoApp/",
                "/runtime/discovery",
                "/API-key viewer",
                "/system/api-keys")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("local")));
        }
    }

    @Test
    void legacyConfigPageRedirectsToConfigsRouteWhenLocalBuildExists() throws Exception {
        Path distDir = createLocalDist();
        configureRoutingService(distDir);
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        mockMvc.perform(get("/config"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/resources/configs"));
    }

    @Test
    void backendConsoleReturnsServiceUnavailableWhenLocalBuildMissing() throws Exception {
        configureRoutingService(tempDir.resolve("missing-dist"));
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendConsoleController(routingService)).build();

        mockMvc.perform(get("/"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Build frontend/dist")));
    }

    private Path createLocalDist() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("frontend-dist-local"));
        Files.writeString(distDir.resolve("index.html"), "<!doctype html><html><body>local</body></html>");
        return distDir;
    }

    private void configureRoutingService(Path distDir) {
        ReflectionTestUtils.setField(routingService, "frontendDistPath", distDir.toString());
    }
}
