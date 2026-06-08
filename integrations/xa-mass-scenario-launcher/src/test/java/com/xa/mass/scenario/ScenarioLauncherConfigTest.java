package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLauncherConfigTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void parsesTaskConfigCredentialFileTimeoutsAndCliOverrides() throws Exception {
        Path secrets = Files.createDirectories(tempDir.resolve("secrets"));
        Files.writeString(secrets.resolve("task-api-key.txt"), "config-task-key\n");
        Path config = writeConfig("""
                {
                  "server": {
                    "baseUrl": "http://127.0.0.1:18088",
                    "connectTimeoutSeconds": 7,
                    "requestTimeoutSeconds": 42
                  },
                  "credentials": {
                    "taskApiKeyFile": "./secrets/task-api-key.txt"
                  },
                  "tasks": [
                    {
                      "project": "demo",
                      "userId": "user",
                      "eventCode": "demo.event",
                      "items": []
                    }
                  ]
                }
                """);

        ScenarioLauncherOptions fromConfig = ScenarioLauncherOptions.parse(new String[]{
                "--config", config.toString()
        });
        assertEquals("http://127.0.0.1:18088", fromConfig.baseUrl());
        assertEquals("config-task-key", fromConfig.taskApiKey());
        assertEquals(Duration.ofSeconds(7), fromConfig.connectTimeout());
        assertEquals(Duration.ofSeconds(42), fromConfig.requestTimeout());

        ScenarioLauncherOptions overridden = ScenarioLauncherOptions.parse(new String[]{
                "--config", config.toString(),
                "--base-url", "http://127.0.0.1:19090",
                "--task-api-key", "cli-task-key"
        });
        assertEquals("http://127.0.0.1:19090", overridden.baseUrl());
        assertEquals("cli-task-key", overridden.taskApiKey());
    }

    @Test
    void taskHelpDoesNotRequireConfigFileToExist() {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                "--help",
                "--config",
                tempDir.resolve("missing.json").toString()
        });

        assertTrue(options.help());
    }

    @Test
    void rejectsWorkerConfigAndScenarioDirInTaskConfig() throws Exception {
        Path workerConfig = writeConfig("""
                {
                  "workers": []
                }
                """);
        IllegalArgumentException workerError = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--config", workerConfig.toString()}));
        assertTrue(workerError.getMessage().contains("workers config is deferred"));

        Path scenarioDirConfig = writeConfig("""
                {
                  "scenarioDir": "integrations/samples/dev/scenario",
                  "tasks": []
                }
                """);
        IllegalArgumentException scenarioDirError = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLauncherOptions.parse(new String[]{"--config", scenarioDirConfig.toString()}));
        assertTrue(scenarioDirError.getMessage().contains("scenarioDir is not supported"));
    }

    @Test
    void workerParserDoesNotReadTaskConfigCredentialFiles() throws Exception {
        Path config = writeConfig("""
                {
                  "credentials": {
                    "taskApiKeyFile": "./secrets/missing-task-key.txt"
                  },
                  "tasks": [
                    {
                      "project": "demo",
                      "userId": "user",
                      "eventCode": "demo.event",
                      "items": []
                    }
                  ]
                }
                """);

        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseWorker(new String[]{
                "--config", config.toString()
        });

        assertEquals(config, options.configPath());
    }

    @Test
    void loadsTaskConfigItemsAndActionMappingRelativeToConfigFile() throws Exception {
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(data.resolve("phones.txt"), """
                +15550000001

                +15550000002
                """);
        Files.writeString(data.resolve("contacts.jsonl"), """
                {"phones":"[\\"+15550000003\\"]","client_id":"bind-1"}
                """);
        Path config = writeConfig("""
                {
                  "runtime": {
                    "taskItemBatchSize": 100
                  },
                  "actions": {
                    "import_contact": {
                      "eventCode": "telegram.importContactsAndDelete",
                      "paramMap": {
                        "phones": "phones",
                        "client_id": "bind_client"
                      },
                      "jsonFields": ["phones"]
                    }
                  },
                  "tasks": [
                    {
                      "project": "telegram",
                      "userId": "loader",
                      "eventCode": "telegram.resolvePhone",
                      "items": {
                        "type": "txt",
                        "path": "./data/phones.txt",
                        "field": "phone"
                      }
                    },
                    {
                      "project": "telegram",
                      "userId": "loader",
                      "action": "import_contact",
                      "items": {
                        "type": "jsonl",
                        "path": "./data/contacts.jsonl"
                      }
                    }
                  ]
                }
                """);

        List<TaskScenarioSpec> specs = ScenarioTaskConfigLoader.load(config, objectMapper);

        assertEquals(2, specs.size());
        assertEquals(100, specs.getFirst().itemBatchSize());
        assertEquals("telegram.resolvePhone", specs.getFirst().body().get("eventCode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phoneItems = (List<Map<String, Object>>) specs.getFirst().body().get("items");
        assertEquals(List.of(Map.of("phone", "+15550000001"), Map.of("phone", "+15550000002")), phoneItems);

        assertEquals("telegram.importContactsAndDelete", specs.get(1).body().get("eventCode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contactItems = (List<Map<String, Object>>) specs.get(1).body().get("items");
        assertEquals("bind-1", contactItems.getFirst().get("bind_client"));
        assertEquals(List.of("+15550000003"), assertInstanceOf(List.class, contactItems.getFirst().get("phones")));
    }

    @Test
    void rejectsConflictingActionEventCode() throws Exception {
        Path config = writeConfig("""
                {
                  "actions": {
                    "resolve_phone": {
                      "eventCode": "telegram.resolvePhone"
                    }
                  },
                  "tasks": [
                    {
                      "project": "telegram",
                      "userId": "loader",
                      "action": "resolve_phone",
                      "eventCode": "telegram.otherEvent",
                      "items": []
                    }
                  ]
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScenarioTaskConfigLoader.load(config, objectMapper));
        assertTrue(error.getMessage().contains("conflicts with action eventCode"));
    }

    private Path writeConfig(String content) throws Exception {
        Path config = Files.createTempFile(tempDir, "scenario", ".json");
        Files.writeString(config, content);
        return config;
    }
}
