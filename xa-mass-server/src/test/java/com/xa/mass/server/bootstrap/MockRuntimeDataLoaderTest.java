package com.xa.mass.server.bootstrap;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerContextSnapshot;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockRuntimeDataLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadIntoUsesExplicitWorkerContextsWithoutDerivingRoutingSignalsFromWorkerGroup() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        MockRuntimeDataLoader loader = loader(
                workersJson(),
                explicitWorkerContextsJson(),
                tasksJson(),
                explicitRulesJson()
        );

        loader.loadInto(runtime);

        assertEquals(2, runtime.workers.size());
        assertEquals(2, runtime.workerContexts.size());
        assertEquals(2, runtime.registeredWorkers.size());
        assertEquals(2, runtime.registeredWorkerContexts.size());
        assertEquals(1, runtime.createdTasks.size());
        assertEquals(2, runtime.rules.size());
        assertTrue(runtime.workers.stream().allMatch(worker -> "OFFLINE".equals(worker.getStatus())));
        assertTrue(runtime.workers.stream().allMatch(worker -> worker.getSupportedProjects().contains("demoApp")));
        assertTrue(runtime.workers.stream().allMatch(worker -> worker.getSupportedProjects().contains("testApp")));

        WorkerContextSnapshot us = runtime.workerContextById("wc-us-1");
        WorkerContextSnapshot gb = runtime.workerContextById("wc-gb-1");
        assertNotNull(us);
        assertNotNull(gb);
        assertTrue(us.getRoutingTags().contains("route-us"));
        assertTrue(gb.getRoutingTags().contains("route-gb"));
        assertEquals("IDLE", us.getStatus());
        assertEquals("IDLE", gb.getStatus());
    }

    @Test
    void loadIntoKeepsWorkersStatelessWhenExplicitContextsAreMissing() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        MockRuntimeDataLoader loader = loader(
                workersJson(),
                null,
                null,
                null
        );

        loader.loadInto(runtime);

        assertEquals(2, runtime.workers.size());
        assertEquals(0, runtime.workerContexts.size());
        assertEquals(0, runtime.createdTasks.size());
    }

    @Test
    void loadIntoKeepsMultipleContextsForSameWorker() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        MockRuntimeDataLoader loader = loader(
                workersJson(),
                multiContextJson(),
                null,
                null
        );

        loader.loadInto(runtime);

        assertEquals(2, runtime.workerContextsFor("worker-us-1").size());
        assertNotNull(runtime.workerContextById("wc-us-1-a"));
        assertNotNull(runtime.workerContextById("wc-us-1-b"));
    }

    @Test
    void loadIntoUsesSdkRegistrationForAllWorkerResources() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        MockRuntimeDataLoader loader = loader(
                defaultStateWorkersJson(),
                defaultStateWorkerContextsJson(),
                null,
                null
        );

        loader.loadInto(runtime);

        assertEquals(1, runtime.registeredWorkers.size());
        assertEquals("OFFLINE", runtime.workers.get(0).getStatus());
        assertEquals("polling", runtime.registeredWorkers.get(0).getTransportHint());

        assertEquals(1, runtime.registeredWorkerContexts.size());
        assertEquals("IDLE", runtime.workerContexts.get(0).getStatus());
        assertTrue(runtime.registeredWorkerContexts.get(0).getRoutingTags().contains("route-us"));
    }

    @Test
    void loadIntoKeepsExistingRulesWhenExplicitRuleConfigIsEmpty() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        RuleDefinition baseline = new RuleDefinition();
        baseline.setId("basic_worker_check");
        runtime.rules.add(baseline);
        MockRuntimeDataLoader loader = loader(
                null,
                null,
                null,
                "[]"
        );

        loader.loadInto(runtime);

        assertEquals(List.of("basic_worker_check"), runtime.ruleIds());
    }

    @Test
    void loadIntoRejectsWorkerFixtureWithoutExplicitTransportIdentity() throws IOException {
        FakeRuntime runtime = new FakeRuntime();
        MockRuntimeDataLoader loader = loader(
                """
                [
                  {
                    "workerId": "worker-missing-adapter",
                    "onlineStrategy": "realtime"
                  }
                ]
                """,
                null,
                null,
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.loadInto(runtime));
        assertEquals("Worker fixture must declare adapterId: worker-missing-adapter", error.getMessage());
    }

    private MockRuntimeDataLoader loader(String workersJson,
                                         String workerContextsJson,
                                         String tasksJson,
                                         String rulesJson) throws IOException {
        return new MockRuntimeDataLoader(
                writeOptional("workers.json", workersJson),
                writeOptional("workerContexts.json", workerContextsJson),
                writeOptional("tasks.json", tasksJson),
                writeOptional("rules.json", rulesJson)
        );
    }

    private String writeOptional(String filename, String content) throws IOException {
        if (content == null) {
            return tempDir.resolve(filename).toString();
        }
        Path path = tempDir.resolve(filename);
        Files.writeString(path, content);
        return path.toString();
    }

    private String workersJson() {
        return """
                [
                  {
                    "workerId": "worker-us-1",
                    "workerGroupId": "POOL-US",
                    "adapterId": "websocket",
                    "onlineStrategy": "realtime",
                    "agentVersion": "1.0.0",
                    "status": "ONLINE",
                    "supportedEventCodes": ["demo.dispatch"]
                  },
                  {
                    "workerId": "worker-gb-1",
                    "workerGroupId": "POOL-GB",
                    "adapterId": "websocket",
                    "onlineStrategy": "realtime",
                    "agentVersion": "1.0.1",
                    "status": "ONLINE",
                    "supportedEventCodes": ["demo.dispatch.gb"]
                  }
                ]
                """;
    }

    private String explicitWorkerContextsJson() {
        return """
                [
                  {
                    "workerContextId": "wc-us-1",
                    "workerId": "worker-us-1",
                    "routingTags": ["route-us"],
                    "status": "IDLE",
                    "attributes": {
                      "carrier": "tmobile"
                    }
                  },
                  {
                    "workerContextId": "wc-gb-1",
                    "workerId": "worker-gb-1",
                    "routingTags": ["route-gb"],
                    "status": "IDLE",
                    "attributes": {
                      "carrier": "vodafone"
                    }
                  }
                ]
                """;
    }

    private String multiContextJson() {
        return """
                [
                  {
                    "workerContextId": "wc-us-1-a",
                    "workerId": "worker-us-1",
                    "routingTags": ["route-us"],
                    "status": "IDLE",
                    "attributes": {
                      "pool": "primary"
                    }
                  },
                  {
                    "workerContextId": "wc-us-1-b",
                    "workerId": "worker-us-1",
                    "routingTags": ["route-us"],
                    "status": "IDLE",
                    "attributes": {
                      "pool": "secondary"
                    }
                  }
                ]
                """;
    }

    private String defaultStateWorkersJson() {
        return """
                [
                  {
                    "workerId": "crawler-worker-001",
                    "workerGroupId": "CRAWLER",
                    "adapterId": "polling",
                    "onlineStrategy": "polling",
                    "supportedProjects": ["crawlerApp"],
                    "supportedEventCodes": ["crawler.fetch-page"],
                    "attributes": {
                      "type": "crawler"
                    }
                  }
                ]
                """;
    }

    private String defaultStateWorkerContextsJson() {
        return """
                [
                  {
                    "workerContextId": "ctx-crawler-worker-001",
                    "workerId": "crawler-worker-001",
                    "routingTags": ["ROUTE-US"],
                    "attributes": {
                      "region": "us"
                    }
                  }
                ]
                """;
    }

    private String tasksJson() {
        return """
                [
                  {
                    "userId": "agent",
                    "project": "demoApp",
                    "sharedConfig": {
                      "textContent": "hello",
                      "routingCode": "us"
                    },
                    "inputs": [
                      {
                        "target": "target-a"
                      }
                    ],
                    "batchSize": 1
                  }
                ]
                """;
    }

    private String explicitRulesJson() {
        return """
                [
                  {
                    "id": "explicit_app_support",
                    "name": "explicit_app_support",
                    "type": "QL_EXPRESS",
                    "content": "worker.supportedProjects != null"
                  },
                  {
                    "id": "explicit_routing_code",
                    "name": "explicit_routing_code",
                    "type": "QL_EXPRESS",
                    "content": "routingCode != null"
                  }
                ]
                """;
    }

    private static final class FakeRuntime implements MassRuntimeControl {
        private final List<WorkerSnapshot> workers = new ArrayList<>();
        private final List<WorkerContextSnapshot> workerContexts = new ArrayList<>();
        private final List<WorkerRegistration> registeredWorkers = new ArrayList<>();
        private final List<WorkerContextRegistration> registeredWorkerContexts = new ArrayList<>();
        private final List<MassTaskShellCreateRequest> createdTasks = new ArrayList<>();
        private final List<RuleDefinition> rules = new ArrayList<>();

        @Override
        public EventResponse dispatchEvent(EventRequest request, PrincipalContext principal) {
            return EventResponse.success(null, request == null ? null : request.getRequestId());
        }

        @Override
        public void registerWorker(WorkerRegistration request) {
            registeredWorkers.add(request);
            List<String> supportedProjects = request.getSupportedProjects();
            if ((supportedProjects == null || supportedProjects.isEmpty()) && !request.getEventBindings().isEmpty()) {
                supportedProjects = request.getEventBindings().stream()
                        .flatMap(binding -> binding.getProjectCodes().stream())
                        .distinct()
                        .toList();
            }
            List<String> supportedEventCodes = request.getSupportedEventCodes();
            if ((supportedEventCodes == null || supportedEventCodes.isEmpty()) && !request.getEventBindings().isEmpty()) {
                supportedEventCodes = request.getEventBindings().stream()
                        .map(com.xa.mass.sdk.model.WorkerEventBinding::getEventCode)
                        .distinct()
                        .toList();
            }
            workers.add(new WorkerSnapshot(
                    request.getWorkerId(),
                    "OFFLINE",
                    null,
                    null,
                    supportedProjects,
                    supportedEventCodes,
                    request.getWorkerGroupId(),
                    request.getAdapterId(),
                    request.getTransportHint(),
                    request.getAttributes(),
                    null,
                    null
            ));
        }

        @Override
        public void registerWorkerContext(WorkerContextRegistration request) {
            registeredWorkerContexts.add(request);
            workerContexts.add(new WorkerContextSnapshot(
                    request.getWorkerContextId(),
                    request.getWorkerId(),
                    request.getProject(),
                    "IDLE",
                    request.getRoutingTags(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    request.getAttributes()
            ));
        }

        @Override
        public TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request) {
            createdTasks.add(request);
            String taskId = "task-" + createdTasks.size();
            return new TaskShellSnapshot(taskId, "fixture-" + createdTasks.size(), "default",
                    request.getProject(), request.getUserId(), request.getContract(), request.getSourceRef());
        }

        @Override
        public void replaceDefaultRules(java.util.Collection<RuleDefinition> rules) {
            this.rules.clear();
            this.rules.addAll(rules);
        }

        @Override
        public boolean approveTask(String taskId) { return false; }

        @Override
        public boolean rejectTask(String taskId) { return false; }

        @Override
        public boolean blockTask(String taskId) { return false; }

        @Override
        public boolean pauseTask(String taskId) { return false; }

        @Override
        public boolean resumeTask(String taskId) { return false; }

        @Override
        public boolean cancelTask(String taskId) { return false; }

        @Override
        public boolean terminateTask(String taskId, String reason) { return false; }

        @Override
        public int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request) { return 0; }

        @Override
        public boolean sealTask(String taskId) { return false; }

        private WorkerContextSnapshot workerContextById(String workerContextId) {
            return workerContexts.stream()
                    .filter(workerContext -> workerContextId.equals(workerContext.getWorkerContextId()))
                    .findFirst()
                    .orElse(null);
        }

        private List<WorkerContextSnapshot> workerContextsFor(String workerId) {
            return workerContexts.stream()
                    .filter(workerContext -> workerId.equals(workerContext.getWorkerId()))
                    .toList();
        }

        private List<String> ruleIds() {
            return rules.stream().map(RuleDefinition::getId).toList();
        }
    }
}
