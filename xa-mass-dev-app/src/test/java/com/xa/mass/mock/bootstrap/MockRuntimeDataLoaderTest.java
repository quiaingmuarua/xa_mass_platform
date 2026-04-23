package com.xa.mass.mock.bootstrap;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(1, runtime.createdTasks.size());
        assertEquals(2, runtime.rules.size());
        assertTrue(runtime.workers.stream().allMatch(worker -> worker.supportsProject("demoApp")));
        assertTrue(runtime.workers.stream().allMatch(worker -> worker.supportsProject("testApp")));

        WorkerContext us = runtime.workerContextById("wc-us-1");
        WorkerContext gb = runtime.workerContextById("wc-gb-1");
        assertNotNull(us);
        assertNotNull(gb);
        assertTrue(us.getRoutingTags().contains("route-us"));
        assertTrue(gb.getRoutingTags().contains("route-gb"));
        assertEquals(WorkerContextStatus.IDLE, us.getStatus());
        assertEquals(WorkerContextStatus.IDLE, gb.getStatus());
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
                    "agentVersion": "1.0.0",
                    "status": "ONLINE"
                  },
                  {
                    "workerId": "worker-gb-1",
                    "workerGroupId": "POOL-GB",
                    "agentVersion": "1.0.1",
                    "status": "ONLINE"
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

    private String tasksJson() {
        return """
                [
                  {
                    "userId": "agent",
                    "project": "demoApp",
                    "taskName": "mock-task",
                    "sharedConfig": {
                      "textContent": "hello",
                      "routingCode": "us"
                    },
                    "inputs": [
                      {
                        "target": "target-a"
                      }
                    ],
                    "batchSize": 1,
                    "defaultMsgMaxRetryCount": 3
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
        private final List<Worker> workers = new ArrayList<>();
        private final List<WorkerContext> workerContexts = new ArrayList<>();
        private final List<MassTaskCreateRequest> createdTasks = new ArrayList<>();
        private final List<RuleDefinition> rules = new ArrayList<>();

        @Override
        public Task createTask(MassTaskCreateRequest request) {
            createdTasks.add(request);
            return new Task();
        }

        @Override
        public void addWorker(Worker worker) {
            workers.add(worker);
        }

        @Override
        public void addWorkerContext(WorkerContext workerContext) {
            workerContexts.add(workerContext);
        }

        @Override
        public void replaceDefaultRules(java.util.Collection<RuleDefinition> rules) {
            this.rules.clear();
            this.rules.addAll(rules);
        }

        @Override
        public void publishTaskEvents() {
        }

        @Override
        public Task getTask(String taskId) { return null; }

        @Override
        public List<Task> getAllTasks() { return List.of(); }

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
        public boolean terminateTask(String taskId, com.xa.mass.base.enums.task.TaskTerminalReason reason) { return false; }

        @Override
        public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) { return 0; }

        @Override
        public boolean sealTask(String taskId) { return false; }

        @Override
        public List<com.xa.mass.base.model.TaskMsg> getTaskMessages(String taskId) { return List.of(); }

        private WorkerContext workerContextById(String workerContextId) {
            return workerContexts.stream()
                    .filter(workerContext -> workerContextId.equals(workerContext.getWorkerContextId()))
                    .findFirst()
                    .orElse(null);
        }

        private List<WorkerContext> workerContextsFor(String workerId) {
            return workerContexts.stream()
                    .filter(workerContext -> workerId.equals(workerContext.getWorkerId()))
                    .toList();
        }

        private List<String> ruleIds() {
            return rules.stream().map(RuleDefinition::getId).toList();
        }
    }
}
