package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCliMainTest {
    @TempDir
    Path tempDir;

    @Test
    void authConfigPrintsFullReadinessFields() throws Exception {
        try (AdminStubServer server = new AdminStubServer()) {
            JsonNode output = runJson("auth", "config", "--base-url", server.baseUrl());

            assertEquals("session", output.path("authMode").asText());
            assertFalse(output.path("operatorHeaderSupported").asBoolean(true));
            assertTrue(output.path("sessionCookieSupported").asBoolean(false));
            assertEquals("X-Mass-Csrf-Token", output.path("csrfHeaderName").asText());
        }
    }

    @Test
    void apiHealthPrintsRouteTimingReport() throws Exception {
        try (AdminStubServer server = new AdminStubServer()
                .addProject("crawlerApp")
                .addEvent("crawler.fetch-page")
                .addRule("basic_worker_check")) {
            JsonNode output = runJson("api", "health", "--config", config(server.baseUrl()).toString());

            assertEquals("passed", output.path("status").asText());
            assertEquals("hard", output.path("gateMode").asText());
            assertEquals("memory-local", output.path("profile").asText());
            assertTrue(output.path("routeTimings").isArray());
            assertEquals(7, output.path("routeTimings").size());
            assertTrue(output.path("routeTimings").findValuesAsText("path")
                    .contains("/api/v1/catalog/worker-capabilities"));
            JsonNode catalogRoute = firstRoute(output, "/api/v1/catalog/worker-group-capabilities");
            assertEquals("SDK_CREDENTIAL_BYPASS read", catalogRoute.path("routeAuthPolicy").asText());
            assertEquals("none", catalogRoute.path("credentialUsedByHealthRunner").asText());
        }
    }

    private JsonNode firstRoute(JsonNode output, String path) {
        for (JsonNode item : output.path("routeTimings")) {
            if (path.equals(item.path("path").asText())) {
                return item;
            }
        }
        throw new AssertionError("route not found: " + path);
    }

    private JsonNode runJson(String... args) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            AdminCliMain.main(args);
        } finally {
            System.setOut(previous);
        }
        return AdminEnvConfig.objectMapper().readTree(output.toString(StandardCharsets.UTF_8));
    }

    private Path config(String baseUrl) throws Exception {
        Files.writeString(tempDir.resolve("operator-password.txt"), "secret", StandardCharsets.UTF_8);
        Path config = tempDir.resolve("admin-env.json");
        Files.writeString(config, """
                {
                  "server":{"baseUrl":"%s","profile":"memory-local"},
                  "operator":{"user":"ops-admin","passwordFile":"operator-password.txt"},
                  "environment":{"mode":"apply","catalogManifest":"catalog.json","rulesManifest":"rules.json"},
                  "credentials":{
                    "taskCredential":{
                      "apiKeyFile":"secrets/task-api-key.txt",
                      "principalId":"scenario-task-producer",
                      "createdForUserId":"ops-admin",
                      "permissions":["task:create","task:edit","task:view"],
                      "projectScopes":["crawlerApp"],
                      "eventScopes":["crawler.fetch-page"],
                      "rawSecretFile":"secrets/task-api-key.txt"
                    },
                    "workerCredentials":{
                      "workerSpecFile":"workers.json",
                      "principalIdTemplate":"scenario-worker-${workerId}",
                      "createdForUserId":"ops-admin",
                      "permissions":["worker:poll"],
                      "projectScopesFromWorkerBindings":true,
                      "eventScopesFromWorkerBindings":true,
                      "rawSecretSource":"workerSpec.workerKey",
                      "workerIdAttribute":"workerId",
                      "maxWorkers":1
                    }
                  },
                  "state":{"mode":"file","markerFile":".state/env-init.json"},
                  "verify":{"requiredProjects":["crawlerApp"],"requiredEvents":["crawler.fetch-page"]}
                }
                """.formatted(baseUrl), StandardCharsets.UTF_8);
        return config;
    }
}
