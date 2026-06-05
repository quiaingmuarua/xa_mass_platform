package com.xa.mass.scenario;

import com.xa.mass.client.http.exception.MassHttpException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLauncherOptionsTest {
    @Test
    void parsesDefaultsAndOverrides() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--register-only",
                "--base-url", "http://localhost:8088/",
                "--task-api-key=task-key",
                "--task-command-api-key", "command-key",
                "--worker-api-key", "worker-key",
                "--scenario-dir", "custom/scenario",
                "--idle-timeout-ms", "1234",
                "--max-polling-workers=7"
        });

        assertTrue(options.registerOnly());
        assertEquals("http://localhost:8088", options.baseUrl());
        assertEquals("task-key", options.taskApiKey());
        assertEquals("command-key", options.taskCommandApiKey());
        assertEquals("worker-key", options.workerApiKey());
        assertEquals(Path.of("custom/scenario"), options.scenarioDir());
        assertEquals(1234L, options.idleTimeoutMs());
        assertEquals(7, options.maxPollingWorkers());
    }

    @Test
    void rejectsUnknownArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--unknown"}));
        assertTrue(error.getMessage().contains("unknown argument"));
    }

    @Test
    void launchIsDefaultMode() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{});

        assertFalse(options.registerOnly());
        assertEquals(60_000L, options.idleTimeoutMs());
        assertEquals(25, options.maxPollingWorkers());
    }

    @Test
    void helpKeepsDefaultLaunchMode() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{"--help"});

        assertTrue(options.help());
        assertFalse(options.registerOnly());
    }

    @Test
    void diagnosesScenarioCatalogMismatchForUnsupportedWorkerEvent() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--scenario-dir", "integrations/samples/dev/scenario"
        });

        String message = ScenarioLauncherMain.diagnoseHttpFailure(options, new MassHttpException(
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
