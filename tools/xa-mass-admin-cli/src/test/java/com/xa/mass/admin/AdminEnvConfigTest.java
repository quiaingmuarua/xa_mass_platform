package com.xa.mass.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminEnvConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesTypedConfigAndDerivesWorkerCredential() throws Exception {
        Fixture fixture = fixture();

        AdminEnvConfig.Loaded loaded = AdminEnvConfig.load(fixture.configFile());
        List<WorkerScenarioSpec> workers = WorkerScenarioManifest.load(
                loaded.resolve(loaded.config().credentials().workerCredentials().workerSpecFile(),
                        "credentials.workerCredentials.workerSpecFile"),
                AdminEnvConfig.objectMapper(),
                loaded.config().credentials().workerCredentials().maxWorkers()
        );
        List<DesiredApiKey> credentials = AdminCredentialPlan.workers(loaded, workers);

        assertEquals(1, credentials.size());
        DesiredApiKey worker = credentials.getFirst();
        assertEquals("scenario-worker-confidence-worker-001", worker.principalId());
        assertEquals(List.of("crawlerApp"), worker.projectScopes());
        assertEquals(List.of("crawler.fetch-page"), worker.eventScopes());
        assertEquals("confidence-worker-001", worker.attributes().get("workerId"));
    }

    @Test
    void rejectsMissingTaskCredentialDesiredState() throws Exception {
        Fixture fixture = fixture();
        String broken = Files.readString(fixture.configFile(), StandardCharsets.UTF_8)
                .replace("\"permissions\":[\"task:create\",\"task:edit\",\"task:view\"],", "\"permissions\":[],");
        Files.writeString(fixture.configFile(), broken, StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminEnvConfig.load(fixture.configFile()));

        assertTrue(error.getMessage().contains("credentials.taskCredential.permissions"));
    }

    @Test
    void rejectsFileStateWithoutMarker() throws Exception {
        Fixture fixture = fixture();
        String broken = Files.readString(fixture.configFile(), StandardCharsets.UTF_8)
                .replace("\"markerFile\":\".state/env-init.json\"", "\"markerFile\":\"\"");
        Files.writeString(fixture.configFile(), broken, StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminEnvConfig.load(fixture.configFile()));

        assertTrue(error.getMessage().contains("state.markerFile"));
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        Fixture fixture = fixture();
        String broken = Files.readString(fixture.configFile(), StandardCharsets.UTF_8)
                .replace("\"server\":{", "\"unexpected\":\"field\",\"server\":{");
        Files.writeString(fixture.configFile(), broken, StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminEnvConfig.load(fixture.configFile()));

        assertTrue(error.getMessage().contains("failed to read admin env config"));
    }

    private Fixture fixture() throws Exception {
        return Fixture.create(tempDir);
    }

    record Fixture(Path configFile) {
        static Fixture create(Path root) throws Exception {
            Path catalog = root.resolve("catalog.json");
            Path rules = root.resolve("rules.json");
            Path workers = root.resolve("workers.json");
            Files.writeString(catalog, """
                    {"events":[{"code":"crawler.fetch-page"}],"projects":[{"code":"crawlerApp"}]}
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
            Path config = root.resolve("admin-env.json");
            Files.writeString(config, """
                    {
                      "server":{"baseUrl":"http://127.0.0.1:1","profile":"memory-local"},
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
                    """, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("operator-password.txt"), "secret", StandardCharsets.UTF_8);
            return new Fixture(config);
        }
    }
}
