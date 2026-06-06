package com.xa.mass.scenario;

import com.xa.mass.client.http.exception.MassHttpException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLauncherOptionsTest {
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
        assertTrue(message.contains("--mass.control-plane.seed.enabled=true"));
        assertTrue(message.contains("file:integrations/samples/dev/scenario/bootstrap.json"));
        assertTrue(message.contains("file:integrations/samples/dev/scenario/rules.json"));
    }
}
