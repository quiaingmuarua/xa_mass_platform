package com.xa.mass.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEnvServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void initAppliesDesiredStateWritesSecretsAndMarkerThenVerifyNoops() throws Exception {
        try (AdminStubServer server = new AdminStubServer()) {
            Path config = config(server.baseUrl());
            AdminEnvService service = service();

            AdminEnvReport first = service.init(AdminEnvConfig.load(config));

            assertTrue(first.applied());
            assertFalse(first.markerMatched());
            assertTrue(Files.exists(tempDir.resolve("secrets/task-api-key.txt")));
            assertTrue(Files.exists(tempDir.resolve(".state/env-init.json")));
            assertTrue(server.hasCredential(Files.readString(tempDir.resolve("secrets/task-api-key.txt"),
                    StandardCharsets.UTF_8).trim()));
            assertTrue(server.hasCredential("confidence-worker-001-key"));
            assertTrue(server.calls().contains("POST /api/v1/control-plane/catalog:sync"));
            assertTrue(server.calls().contains("POST /api/v1/control-plane/rules:sync"));

            int callsAfterFirst = server.calls().size();
            AdminEnvReport second = service.init(AdminEnvConfig.load(config));

            assertFalse(second.applied());
            assertTrue(second.markerMatched());
            var secondCalls = server.calls().subList(callsAfterFirst, server.calls().size());
            assertTrue(secondCalls.contains("GET /api/v1/projects"));
            assertTrue(secondCalls.contains("GET /api/v1/catalog/events"));
            assertFalse(secondCalls.contains("POST /api/v1/control-plane/catalog:sync"));
            assertFalse(secondCalls.contains("POST /api/v1/control-plane/rules:sync"));
            assertFalse(secondCalls.contains("POST /api/v1/api-keys"));
        }
    }

    @Test
    void verifyFailsOperatorReadinessBeforeCatalogDiagnostics() throws Exception {
        try (AdminStubServer server = new AdminStubServer().withAuthMode("dev-header")) {
            EnvInitFailure failure = assertThrows(EnvInitFailure.class,
                    () -> service().verify(AdminEnvConfig.load(config(server.baseUrl()))));

            assertEquals("operator-auth/readiness", failure.category());
            assertTrue(failure.getMessage().contains("session operator auth"));
        }
    }

    @Test
    void verifyRejectsStaleCredentialEvenWhenRawSecretAuthenticates() throws Exception {
        try (AdminStubServer server = new AdminStubServer()) {
            Path config = config(server.baseUrl());
            AdminEnvService service = service();
            service.init(AdminEnvConfig.load(config));

            // Change desired worker binding while reusing the old raw secret.
            String changed = Files.readString(config, StandardCharsets.UTF_8)
                    .replace("\"workerIdAttribute\":\"workerId\"", "\"workerIdAttribute\":\"otherWorkerId\"");
            Files.writeString(config, changed, StandardCharsets.UTF_8);

            EnvInitFailure failure = assertThrows(EnvInitFailure.class,
                    () -> service.verify(AdminEnvConfig.load(config)));

            assertEquals("worker-key", failure.category());
            assertTrue(failure.getMessage().contains("stale/mismatched"));
        }
    }

    @Test
    void memoryStateDoesNotWriteOrUseMarker() throws Exception {
        try (AdminStubServer server = new AdminStubServer()) {
            Path config = config(server.baseUrl());
            String memory = Files.readString(config, StandardCharsets.UTF_8)
                    .replace("\"state\":{\"mode\":\"file\",\"markerFile\":\".state/env-init.json\"}",
                            "\"state\":{\"mode\":\"memory\",\"markerFile\":\".state/env-init.json\"}");
            Files.writeString(config, memory, StandardCharsets.UTF_8);

            AdminEnvService service = service();
            AdminEnvReport first = service.init(AdminEnvConfig.load(config));
            int callsAfterFirst = server.calls().size();
            AdminEnvReport second = service.init(AdminEnvConfig.load(config));

            assertTrue(first.applied());
            assertTrue(second.applied());
            assertFalse(second.markerMatched());
            assertFalse(Files.exists(tempDir.resolve(".state/env-init.json")));
            assertTrue(server.calls().subList(callsAfterFirst, server.calls().size())
                    .contains("POST /api/v1/control-plane/catalog:sync"));
        }
    }

    private AdminEnvService service() {
        return new AdminEnvService(
                AdminEnvConfig.objectMapper(),
                Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private Path config(String baseUrl) throws Exception {
        Path catalog = tempDir.resolve("catalog.json");
        Path rules = tempDir.resolve("rules.json");
        Path workers = tempDir.resolve("workers.json");
        Files.writeString(catalog, """
                {
                  "events":[{"code":"crawler.fetch-page"}],
                  "projects":[{"code":"crawlerApp"}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(rules, """
                {"rules":[{"id":"basic_worker_check","type":"QL_EXPRESS","content":"true"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(workers, """
                [{
                  "workerId":"confidence-worker-001",
                  "workerKey":"confidence-worker-001-key",
                  "eventBindings":[{"eventCode":"crawler.fetch-page","projectCodes":["crawlerApp"]}]
                }]
                """, StandardCharsets.UTF_8);
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
