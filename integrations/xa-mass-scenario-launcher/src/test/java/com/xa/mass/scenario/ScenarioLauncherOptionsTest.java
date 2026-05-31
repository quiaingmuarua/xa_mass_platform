package com.xa.mass.scenario;

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
                "--bootstrap-key=bootstrap-key",
                "--scenario-dir", "custom/scenario"
        });

        assertTrue(options.registerOnly());
        assertEquals("http://localhost:8088", options.baseUrl());
        assertEquals("task-key", options.taskApiKey());
        assertEquals("command-key", options.taskCommandApiKey());
        assertEquals("worker-key", options.workerApiKey());
        assertEquals("bootstrap-key", options.bootstrapKey());
        assertEquals(Path.of("custom/scenario"), options.scenarioDir());
    }

    @Test
    void rejectsUnknownArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--unknown"}));
        assertTrue(error.getMessage().contains("unknown argument"));
    }

    @Test
    void helpDoesNotImplyRegisterOnly() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{"--help"});

        assertTrue(options.help());
        assertFalse(options.registerOnly());
    }
}
