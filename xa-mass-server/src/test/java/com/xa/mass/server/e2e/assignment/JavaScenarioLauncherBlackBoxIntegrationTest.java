package com.xa.mass.server.e2e.assignment;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ExternalJavaScenarioLauncherProcess;
import com.xa.mass.server.e2e.support.ReviewReadModelSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.bootstrap.enabled=false"
        }
)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JavaScenarioLauncherBlackBoxIntegrationTest extends ReviewReadModelSampleE2eTest {
    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String TASK_API_KEY = "ijs-task-key";
    private static final String WORKER_ID = "ijs-scenario-worker-001";
    private static final String WORKER_GROUP_ID = "ijs-scenario-group";
    private static final String WEBSOCKET_WORKER_ID = "ijs-scenario-ws-worker-001";
    private static final String WEBSOCKET_WORKER_GROUP_ID = "ijs-scenario-ws-group";
    private static final Pattern CREATED_TASK_PATTERN = Pattern.compile("created task .* taskId=([^\\s]+)");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void scenarioLauncherRegistersTopologySeedsTaskAndCompletesPollingDispatch(@TempDir Path scenarioDir)
            throws Exception {
        writePollingScenario(scenarioDir);
        registerPollingHostFixture();

        String output;
        String baseUrl = "http://127.0.0.1:" + port;
        try (ExternalJavaScenarioLauncherProcess workerLauncher = ExternalJavaScenarioLauncherProcess.startWorkerLauncher(
                baseUrl,
                null,
                scenarioDir,
                TASK_API_KEY)) {
            waitForWorkerPresenceOnline(
                    WORKER_ID,
                    40,
                    250L,
                    () -> workerLauncher.assertAlive(
                            "Java scenario worker launcher exited before polling worker became online"),
                    workerLauncher::capturedOutput
            );
            output = ExternalJavaScenarioLauncherProcess.runTaskLauncher(
                    baseUrl,
                    scenarioDir,
                    TASK_API_KEY,
                    Duration.ofSeconds(90));
            String taskId = extractCreatedTaskId(output);
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());
        }

        String taskId = extractCreatedTaskId(output);
        TaskSnapshot terminalView = fetchTaskSnapshot(taskId);
        assertEquals(WORKER_ID, terminalView.messages().getFirst().get("latestAttemptWorkerId"));
        Object outputObject = terminalView.messages().getFirst().get("output");
        assertInstanceOf(Map.class, outputObject);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerOutput = (Map<String, Object>) outputObject;
        assertEquals("java-scenario-launcher-polling", workerOutput.get("integrationProbe"));
        assertEquals(WORKER_ID, workerOutput.get("workerId"));
        assertEquals("ijs.polling.echo", workerOutput.get("eventCode"));

        Object workerProfileObject = workerOutput.get("workerProfile");
        assertInstanceOf(Map.class, workerProfileObject);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
        assertEquals("java-scenario-launcher", workerProfile.get("runtime"));
        assertEquals(WORKER_GROUP_ID, workerProfile.get("workerGroupId"));
    }

    @Test
    void scenarioLauncherCompletesWebSocketDispatchThroughSdkSession(@TempDir Path scenarioDir)
            throws Exception {
        writeWebSocketScenario(scenarioDir);
        registerWebSocketHostFixture();

        String output;
        String baseUrl = "http://127.0.0.1:" + port;
        String webSocketUrl = "ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws";
        try (ExternalJavaScenarioLauncherProcess workerLauncher = ExternalJavaScenarioLauncherProcess.startWorkerLauncher(
                baseUrl,
                webSocketUrl,
                scenarioDir,
                TASK_API_KEY)) {
            waitForWorkerPresenceOnline(
                    WEBSOCKET_WORKER_ID,
                    40,
                    250L,
                    () -> workerLauncher.assertAlive(
                            "Java scenario worker launcher exited before websocket worker became online"),
                    workerLauncher::capturedOutput
            );
            output = ExternalJavaScenarioLauncherProcess.runTaskLauncher(
                    baseUrl,
                    scenarioDir,
                    TASK_API_KEY,
                    Duration.ofSeconds(90));
            String taskId = extractCreatedTaskId(output);
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());
        }

        String taskId = extractCreatedTaskId(output);
        TaskSnapshot terminalView = fetchTaskSnapshot(taskId);
        assertEquals(WEBSOCKET_WORKER_ID, terminalView.messages().getFirst().get("latestAttemptWorkerId"));
        Object outputObject = terminalView.messages().getFirst().get("output");
        assertInstanceOf(Map.class, outputObject);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerOutput = (Map<String, Object>) outputObject;
        assertEquals("java-scenario-launcher-websocket", workerOutput.get("integrationProbe"));
        assertEquals(WEBSOCKET_WORKER_ID, workerOutput.get("workerId"));
        assertEquals("ijs.websocket.echo", workerOutput.get("eventCode"));

        Object workerProfileObject = workerOutput.get("workerProfile");
        assertInstanceOf(Map.class, workerProfileObject);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
        assertEquals("java-scenario-launcher", workerProfile.get("runtime"));
        assertEquals("websocket", workerProfile.get("transport"));
        assertEquals(WEBSOCKET_WORKER_GROUP_ID, workerProfile.get("workerGroupId"));
    }

    private static String extractCreatedTaskId(String output) {
        var matcher = CREATED_TASK_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new AssertionError("Scenario launcher did not report a created task. Output:\n" + output);
        }
        return matcher.group(1);
    }

    private void registerPollingHostFixture() {
        registerScenarioCatalog(
                "ijsApp",
                "IJS App",
                "Scenario launcher black-box proof project.",
                "ijs.polling.echo",
                "IJS Polling Echo",
                "Minimal scenario-launcher polling proof event.");
        registerScenarioCredentialPrincipals("ijsApp", "ijs.polling.echo", "ijs-worker-key", WORKER_ID);
        replaceScenarioRules("ijs-worker-online", "ijs-event-capability");
    }

    private void registerWebSocketHostFixture() {
        registerScenarioCatalog(
                "ijsWsApp",
                "IJS WebSocket App",
                "Scenario launcher websocket black-box proof project.",
                "ijs.websocket.echo",
                "IJS WebSocket Echo",
                "Minimal scenario-launcher websocket proof event.");
        registerScenarioCredentialPrincipals("ijsWsApp", "ijs.websocket.echo", "ijs-ws-worker-key", WEBSOCKET_WORKER_ID);
        replaceScenarioRules("ijs-ws-worker-online", "ijs-ws-event-capability");
    }

    private void registerScenarioCatalog(String projectCode,
                                         String projectName,
                                         String projectDescription,
                                         String eventCode,
                                         String eventName,
                                         String eventDescription) {
        sdkApp().registerEventDefinition(EventDefinition.builder()
                .code(eventCode)
                .name(eventName)
                .description(eventDescription)
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of(projectCode))
                .build());
        sdkApp().registerProject(ProjectDefinition.builder()
                .code(projectCode)
                .name(projectName)
                .description(projectDescription)
                .eventCodes(List.of(eventCode))
                .build());
    }

    private void registerScenarioCredentialPrincipals(String projectCode,
                                                      String eventCode,
                                                      String workerCredential,
                                                      String workerId) {
        sdkApp().projectCredential(CredentialPrincipalRegistration.builder()
                .principalId("ijs-task-api-key")
                .credential(TASK_API_KEY)
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .build());
        sdkApp().projectCredential(CredentialPrincipalRegistration.builder()
                .principalId(workerId)
                .credential(workerCredential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .attributes(Map.of("workerId", workerId))
                .build());
    }

    private void replaceScenarioRules(String onlineRuleId, String capabilityRuleId) {
        sdkApp().replaceDefaultRules(List.of(
                rule(onlineRuleId, "hasWorkerSchedulingResource == true"),
                rule(capabilityRuleId, "supportsEvent == true")
        ));
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setName(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        rule.setEnabled(true);
        return rule;
    }

    private MassSdkApplication sdkApp() {
        if (app == null) {
            throw new IllegalStateException("MassSdkApplication is not available for this E2E fixture");
        }
        return app;
    }

    private static void writePollingScenario(Path scenarioDir) throws Exception {
        Files.writeString(scenarioDir.resolve("bootstrap.json"), """
                {
                  "events": [
                    {
                      "code": "ijs.polling.echo",
                      "name": "IJS Polling Echo",
                      "description": "Minimal scenario-launcher polling proof event.",
                      "payloadTypes": ["JSON"],
                      "taskModes": ["SINGLE_RUN"],
                      "projectCodes": ["ijsApp"]
                    }
                  ],
                  "projects": [
                    {
                      "code": "ijsApp",
                      "name": "IJS App",
                      "description": "Scenario launcher black-box proof project.",
                      "eventCodes": ["ijs.polling.echo"]
                    }
                  ],
                  "apiKeys": [
                    {
                      "principalId": "ijs-task-api-key",
                      "rawSecret": "ijs-task-key",
                      "createdForUserId": "ops-admin",
                      "devOnly": true,
                      "permissions": ["task:create"],
                      "projectScopes": ["ijsApp"],
                      "eventScopes": ["ijs.polling.echo"]
                    },
                    {
                      "principalId": "ijs-scenario-worker-001",
                      "rawSecret": "ijs-worker-key",
                      "createdForUserId": "ops-admin",
                      "devOnly": true,
                      "permissions": ["worker:poll"],
                      "projectScopes": ["ijsApp"],
                      "eventScopes": ["ijs.polling.echo"],
                      "attributes": {
                        "workerId": "ijs-scenario-worker-001"
                      }
                    }
                  ]
                }
                """);
        Files.writeString(scenarioDir.resolve("rules.json"), """
                {
                  "rules": [
                    {
                      "id": "ijs-worker-online",
                      "name": "IJS worker online",
                      "type": "QL_EXPRESS",
                      "content": "hasWorkerSchedulingResource == true",
                      "priority": 1,
                      "enabled": true
                    },
                    {
                      "id": "ijs-event-capability",
                      "name": "IJS event capability",
                      "type": "QL_EXPRESS",
                      "content": "supportsEvent == true",
                      "priority": 2,
                      "enabled": true
                    }
                  ]
                }
                """);
        Files.writeString(scenarioDir.resolve("workers.json"), """
                [
                  {
                    "workerId": "ijs-scenario-worker-001",
                    "workerKey": "ijs-worker-key",
                    "workerGroupId": "ijs-scenario-group",
                    "adapterNodeId": "ijs-scenario-polling-node",
                    "adapterId": "polling",
                    "transportHint": "polling",
                    "startMode": "api-online",
                    "attributes": {
                      "runtime": "java-scenario-launcher",
                      "routingTags": "us"
                    },
                    "eventBindings": [
                      {
                        "eventCode": "ijs.polling.echo",
                        "projectCodes": ["ijsApp"]
                      }
                    ]
                  }
                ]
                """);
        Files.writeString(scenarioDir.resolve("tasks.json"), """
                [
                  {
                    "approve": true,
                    "body": {
                      "project": "ijsApp",
                      "userId": "ijs-scenario-launcher",
                      "sourceRef": "ijs-scenario-launcher-black-box",
                      "sharedConfig": {
                        "routingCode": "us",
                        "workerGroupId": "ijs-scenario-group",
                        "reviewMaterializationMode": "terminal"
                      },
                      "executionSpec": {
                        "batchSize": 1
                      },
                      "eventCode": "ijs.polling.echo",
                      "items": [
                        {
                          "payload": "hello"
                        }
                      ]
                    }
                  }
                ]
                """);
    }

    private static void writeWebSocketScenario(Path scenarioDir) throws Exception {
        Files.writeString(scenarioDir.resolve("bootstrap.json"), """
                {
                  "events": [
                    {
                      "code": "ijs.websocket.echo",
                      "name": "IJS WebSocket Echo",
                      "description": "Minimal scenario-launcher websocket proof event.",
                      "payloadTypes": ["JSON"],
                      "taskModes": ["SINGLE_RUN"],
                      "projectCodes": ["ijsWsApp"]
                    }
                  ],
                  "projects": [
                    {
                      "code": "ijsWsApp",
                      "name": "IJS WebSocket App",
                      "description": "Scenario launcher websocket black-box proof project.",
                      "eventCodes": ["ijs.websocket.echo"]
                    }
                  ],
                  "apiKeys": [
                    {
                      "principalId": "ijs-task-api-key",
                      "rawSecret": "ijs-task-key",
                      "createdForUserId": "ops-admin",
                      "devOnly": true,
                      "permissions": ["task:create"],
                      "projectScopes": ["ijsWsApp"],
                      "eventScopes": ["ijs.websocket.echo"]
                    },
                    {
                      "principalId": "ijs-scenario-ws-worker-001",
                      "rawSecret": "ijs-ws-worker-key",
                      "createdForUserId": "ops-admin",
                      "devOnly": true,
                      "permissions": ["worker:poll"],
                      "projectScopes": ["ijsWsApp"],
                      "eventScopes": ["ijs.websocket.echo"],
                      "attributes": {
                        "workerId": "ijs-scenario-ws-worker-001"
                      }
                    }
                  ]
                }
                """);
        Files.writeString(scenarioDir.resolve("rules.json"), """
                {
                  "rules": [
                    {
                      "id": "ijs-ws-worker-online",
                      "name": "IJS websocket worker online",
                      "type": "QL_EXPRESS",
                      "content": "hasWorkerSchedulingResource == true",
                      "priority": 1,
                      "enabled": true
                    },
                    {
                      "id": "ijs-ws-event-capability",
                      "name": "IJS websocket event capability",
                      "type": "QL_EXPRESS",
                      "content": "supportsEvent == true",
                      "priority": 2,
                      "enabled": true
                    }
                  ]
                }
                """);
        Files.writeString(scenarioDir.resolve("workers.json"), """
                [
                  {
                    "workerId": "ijs-scenario-ws-worker-001",
                    "workerKey": "ijs-ws-worker-key",
                    "workerGroupId": "ijs-scenario-ws-group",
                    "adapterNodeId": "ijs-scenario-websocket-node",
                    "adapterId": "websocket",
                    "transportHint": "realtime",
                    "startMode": "websocket",
                    "attributes": {
                      "runtime": "java-scenario-launcher",
                      "routingTags": "us"
                    },
                    "eventBindings": [
                      {
                        "eventCode": "ijs.websocket.echo",
                        "projectCodes": ["ijsWsApp"]
                      }
                    ]
                  }
                ]
                """);
        Files.writeString(scenarioDir.resolve("tasks.json"), """
                [
                  {
                    "approve": true,
                    "body": {
                      "project": "ijsWsApp",
                      "userId": "ijs-scenario-launcher",
                      "sourceRef": "ijs-scenario-launcher-websocket-black-box",
                      "sharedConfig": {
                        "routingCode": "us",
                        "workerGroupId": "ijs-scenario-ws-group",
                        "reviewMaterializationMode": "terminal"
                      },
                      "executionSpec": {
                        "batchSize": 1
                      },
                      "eventCode": "ijs.websocket.echo",
                      "items": [
                        {
                          "payload": "hello-websocket"
                        }
                      ]
                    }
                  }
                ]
                """);
    }
}
