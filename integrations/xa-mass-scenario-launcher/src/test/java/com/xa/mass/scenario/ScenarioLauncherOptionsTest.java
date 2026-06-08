package com.xa.mass.scenario;

import com.xa.mass.client.http.exception.MassHttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLauncherOptionsTest {
    @TempDir
    private Path tempDir;

    @Test
    void parsesDefaultsAndOverrides() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--base-url", "http://localhost:8088/",
                "--task-api-key=task-key",
                "--worker-api-key", "worker-key",
                "--scenario-dir", "custom/scenario",
                "--max-polling-workers=7"
        });

        assertEquals("http://localhost:8088", options.baseUrl());
        assertEquals("task-key", options.taskApiKey());
        assertEquals("worker-key", options.workerApiKey());
        assertEquals(Path.of("custom/scenario"), options.scenarioDir());
        assertEquals(7, options.maxPollingWorkers());
    }

    @Test
    void rejectsUnknownArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--unknown"}));
        assertTrue(error.getMessage().contains("unknown argument"));
    }

    @Test
    void rejectsRetiredTaskCommandCredentialFlag() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--task-command-api-key", "command-key"}));
        assertTrue(error.getMessage().contains("unknown argument"));
    }

    @Test
    void parsesDefaultRuntimeOptions() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{});

        assertEquals("crawler-task-api-key", options.taskApiKey());
        assertEquals(25, options.maxPollingWorkers());
    }

    @Test
    void readsWorkerApiKeyFromExplicitFile() throws Exception {
        Path workerKeyFile = tempDir.resolve("worker-api-key.txt");
        Files.writeString(workerKeyFile, "worker-key-from-file\n", StandardCharsets.UTF_8);

        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseWorker(new String[]{
                "--worker-api-key-file", workerKeyFile.toString()
        });

        assertEquals("worker-key-from-file", options.workerApiKey());
    }

    @Test
    void workerLauncherDoesNotReadGlobalWorkerApiKeyFileByDefault() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseWorker(new String[]{});

        assertEquals(null, options.workerApiKey());
    }

    @Test
    void helpIsParsedByBothEntrypoints() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{"--help"});

        assertTrue(options.help());
        assertTrue(ScenarioLauncherOptions.taskHelpText().contains("xa-mass-scenario-task-launcher.jar"));
        assertTrue(ScenarioLauncherOptions.workerHelpText().contains("xa-mass-scenario-worker-launcher.jar"));
    }

    @Test
    void rejectsRetiredCombinedRegisterOnlyFlag() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--register-only"}));
        assertTrue(error.getMessage().contains("unknown argument"));
    }

    @Test
    void diagnosesScenarioCatalogMismatchForUnsupportedWorkerEvent() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--scenario-dir", "integrations/samples/dev/scenario"
        });

        String message = ScenarioFailureDiagnostics.diagnoseHttpFailure(options, new MassHttpException(
                "POST",
                "/worker-api/v1/worker-groups",
                400,
                "{\"code\":400,\"msg\":\"Unsupported worker event: crawler.fetch-page\",\"data\":null}"
        ));

        assertTrue(message.contains("server catalog does not contain an event"));
        assertTrue(message.contains("integrations/samples/dev/scenario/workers.json"));
        assertTrue(message.contains("xa-mass-scenario-credential-bootstrap"));
        assertTrue(!message.contains("--mass.control-plane.seed.enabled=true"));
    }

    @Test
    void diagnosesMissingTaskApiKeyForScenarioTaskCreate() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--scenario-dir", "integrations/samples/dev/scenario"
        });

        String message = ScenarioFailureDiagnostics.diagnoseHttpFailure(options, new MassHttpException(
                "POST",
                "/api/v1/tasks",
                401,
                "{\"code\":401,\"msg\":\"Invalid or missing API-key credential\",\"data\":null}"
        ));

        assertTrue(message.contains("task API-key credential does not exist"));
        assertTrue(message.contains("--task-api-key"));
        assertTrue(message.contains("MASS_TASK_API_KEY"));
        assertTrue(message.contains("xa-mass-scenario-credential-bootstrap"));
        assertTrue(!message.contains("--mass.control-plane.seed.enabled=true"));
    }

    @Test
    void diagnosesMissingWorkerApiKeyForScenarioWorkerRegistration() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseWorker(new String[]{
                "--scenario-dir", "integrations/samples/dev/scenario"
        });

        String message = ScenarioFailureDiagnostics.diagnoseHttpFailure(options, new MassHttpException(
                "POST",
                "/worker-api/v1/worker-groups",
                401,
                "{\"code\":401,\"msg\":\"Invalid or missing worker credential\",\"data\":null}"
        ));

        assertTrue(message.contains("worker API-key credential"));
        assertTrue(message.contains("workerId-bound credentials"));
        assertTrue(message.contains("xa-mass-scenario-credential-bootstrap"));
        assertTrue(message.contains("MASS_WORKER_API_KEY"));
    }
}
