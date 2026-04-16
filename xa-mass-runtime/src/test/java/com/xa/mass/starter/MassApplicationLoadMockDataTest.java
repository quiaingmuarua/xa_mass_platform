package com.xa.mass.starter;

import com.google.gson.JsonParser;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.storage.InMemoryWorkerStorage;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MassApplicationLoadMockDataTest {

    @Test
    void loadMockDataUsesExplicitWorkerContextsWithoutDerivingRoutingSignalsFromWorkerGroup() {
        TestHarness harness = createHarness();
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), explicitWorkerContextConfig()
        );

        application.loadMockData(harness.engine(), explicitWorkerContextConfig());

        List<Worker> workers = harness.workerManager().getAllWorkers();
        List<WorkerContext> workerContexts = harness.workerManager().getAllWorkerContexts();

        assertEquals(2, workers.size());
        assertEquals(2, workerContexts.size());
        assertEquals(1, harness.createdTasks().get());
        assertTrue(workers.stream().allMatch(worker -> worker.supportsProject("demoApp")));
        assertTrue(workers.stream().allMatch(worker -> worker.supportsProject("testApp")));

        WorkerContext usWc = harness.workerManager().getWorkerContext("worker-us-1");
        WorkerContext gbWc = harness.workerManager().getWorkerContext("worker-gb-1");
        assertNotNull(usWc);
        assertNotNull(gbWc);
        assertEquals("route-us", usWc.getChannel());
        assertEquals("route-gb", gbWc.getChannel());
        assertEquals("us", usWc.getAttributes().get("country"));
        assertEquals("gb", gbWc.getAttributes().get("country"));
        assertEquals(WorkerContextStatus.IDLE, usWc.getStatus());
        assertEquals(WorkerContextStatus.IDLE, gbWc.getStatus());
    }

    @Test
    void loadMockDataSeedsMinimalWorkerContextsWhenExplicitDataIsMissing() {
        TestHarness harness = createHarness();
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), fallbackSeedConfig()
        );

        application.loadMockData(harness.engine(), fallbackSeedConfig());

        List<Worker> workers = harness.workerManager().getAllWorkers();
        List<WorkerContext> workerContexts = harness.workerManager().getAllWorkerContexts();

        assertEquals(2, workers.size());
        assertEquals(workers.size(), workerContexts.size());
        assertEquals(0, harness.createdTasks().get());
        assertTrue(workerContexts.stream().allMatch(wc -> wc.getStatus() == WorkerContextStatus.IDLE));
        assertTrue(workerContexts.stream().allMatch(wc -> wc.getWorkerId() != null && wc.getWorkerContextId() != null));
        assertTrue(workerContexts.stream().allMatch(wc -> wc.getChannel() == null));
        assertTrue(workerContexts.stream().allMatch(wc -> wc.getAttributes().isEmpty()));
        assertNull(harness.workerManager().getWorkerContext("missing-worker"));
    }

    @Test
    void loadMockDataKeepsMultipleContextsForSameWorker() {
        TestHarness harness = createHarness();
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), multiContextConfig()
        );

        application.loadMockData(harness.engine(), multiContextConfig());

        List<WorkerContext> workerContexts = harness.workerManager().getWorkerContexts("worker-us-1");
        assertEquals(2, workerContexts.size());
        assertNotNull(harness.workerManager().getWorkerContextById("wc-us-1-a"));
        assertNotNull(harness.workerManager().getWorkerContextById("wc-us-1-b"));
    }

    @Test
    void loadMockDataReplacesDefaultRulesWhenExplicitRuleConfigIsProvided() {
        TestHarness harness = createHarness();
        EngineConfig config = explicitRuleConfig(harness.ruleManager());
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), config
        );

        List<String> beforeRuleIds = harness.ruleManager().getDefaultRules().stream()
                .map(com.xa.mass.engine.rules.RuleDefinition::getId)
                .sorted()
                .toList();
        assertTrue(beforeRuleIds.contains("basic_worker_check"));

        application.loadMockData(harness.engine(), config);

        List<String> afterRuleIds = harness.ruleManager().getDefaultRules().stream()
                .map(com.xa.mass.engine.rules.RuleDefinition::getId)
                .sorted()
                .toList();
        assertEquals(List.of("explicit_app_support", "explicit_routing_code"), afterRuleIds);
        assertTrue(harness.ruleManager().getEvaluator(RuleType.QL_EXPRESS).isPresent());
    }

    @Test
    void loadMockDataKeepsDefaultRulesWhenExplicitRuleConfigIsEmpty() {
        TestHarness harness = createHarness();
        EngineConfig config = emptyRuleConfig(harness.ruleManager());
        MassApplication application = new MassApplication(
                harness.engine(), 8088, "/ws", new GatewayConfig(), config
        );

        List<String> beforeRuleIds = harness.ruleManager().getDefaultRules().stream()
                .map(com.xa.mass.engine.rules.RuleDefinition::getId)
                .sorted()
                .toList();

        application.loadMockData(harness.engine(), config);

        List<String> afterRuleIds = harness.ruleManager().getDefaultRules().stream()
                .map(com.xa.mass.engine.rules.RuleDefinition::getId)
                .sorted()
                .toList();
        assertEquals(beforeRuleIds, afterRuleIds);
        assertTrue(harness.ruleManager().getEvaluator(RuleType.QL_EXPRESS).isPresent());
    }

    private TestHarness createHarness() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        MassEngine engine = mock(MassEngine.class);
        AtomicInteger createdTasks = new AtomicInteger();
        RuleManager<Map<String, Object>> ruleManager = new EngineConfig().getRuleManager();

        when(engine.getWorkerManager()).thenReturn(workerManager);
        doAnswer(invocation -> {
            Worker worker = invocation.getArgument(0);
            workerManager.addWorker(worker);
            return null;
        }).when(engine).addWorker(any(Worker.class));
        doAnswer(invocation -> {
            WorkerContext wc = invocation.getArgument(0);
            workerManager.addWorkerContext(wc.getWorkerId(), wc);
            return null;
        }).when(engine).addWorkerContext(any(WorkerContext.class));
        doAnswer(invocation -> {
            createdTasks.incrementAndGet();
            return new Task();
        }).when(engine).createTask(any(TaskCreateRequestDto.class));
        return new TestHarness(engine, workerManager, createdTasks, ruleManager);
    }

    private EngineConfig explicitWorkerContextConfig() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "workers": [
                    {
                      "MODEL": "Worker",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerId": "worker-us-1",
                        "workerGroupId": "POOL-US",
                        "agentVersion": "1.0.0",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    },
                    {
                      "MODEL": "Worker",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerId": "worker-gb-1",
                        "workerGroupId": "POOL-GB",
                        "agentVersion": "1.0.1",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ],
                  "workerContexts": [
                    {
                      "MODEL": "WorkerContext",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerContextId": "wc-us-1",
                        "workerId": "worker-us-1",
                        "channel": "route-us",
                        "status": "IDLE",
                        "attributes": {
                          "country": "US",
                          "carrier": "tmobile"
                        }
                      }
                    },
                    {
                      "MODEL": "WorkerContext",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerContextId": "wc-gb-1",
                        "workerId": "worker-gb-1",
                        "channel": "route-gb",
                        "status": "IDLE",
                        "attributes": {
                          "country": "GB",
                          "carrier": "o2"
                        }
                      }
                    }
                  ],
                  "tasks": [
                    {
                      "MODEL": "TaskCreateRequestDto",
                      "COUNT": 1,
                      "FIELDS": {
                        "taskName": "task-1",
                        "project": "demoApp",
                        "routingCode": "us",
                        "userId": "agent",
                        "sharedConfig": {"textContent": "smoke"},
                        "batchSize": 1,
                        "targetList": ["target-1"]
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private EngineConfig fallbackSeedConfig() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "workers": [
                    {
                      "MODEL": "Worker",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerId": "worker-us-1",
                        "workerGroupId": "POOL-US",
                        "agentVersion": "1.0.0",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    },
                    {
                      "MODEL": "Worker",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerId": "worker-gb-1",
                        "workerGroupId": "POOL-GB",
                        "agentVersion": "1.0.1",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private EngineConfig explicitRuleConfig(RuleManager<Map<String, Object>> ruleManager) {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setRuleManager(ruleManager);
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "rules": [
                    {
                      "MODEL": "RuleDefinition",
                      "COUNT": 1,
                      "FIELDS": {
                        "id": "explicit_routing_code",
                        "name": "Explicit routing rule",
                        "description": "Uses worker-context country attribute",
                        "desc": "Uses worker-context country attribute",
                        "type": "QL_EXPRESS",
                        "content": "workerContextAttributes['country'] == taskRoutingCode",
                        "priority": 1,
                        "enabled": true
                      }
                    },
                    {
                      "MODEL": "RuleDefinition",
                      "COUNT": 1,
                      "FIELDS": {
                        "id": "explicit_app_support",
                        "name": "Explicit app support",
                        "description": "Worker must support project",
                        "desc": "Worker must support project",
                        "type": "QL_EXPRESS",
                        "content": "supportsProject == true",
                        "priority": 2,
                        "enabled": true
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private EngineConfig multiContextConfig() {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "workers": [
                    {
                      "MODEL": "Worker",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerId": "worker-us-1",
                        "workerGroupId": "POOL-US",
                        "agentVersion": "1.0.0",
                        "status": "ONLINE",
                        "supportedProjects": ["demoApp", "testApp"]
                      }
                    }
                  ],
                  "workerContexts": [
                    {
                      "MODEL": "WorkerContext",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerContextId": "wc-us-1-a",
                        "workerId": "worker-us-1",
                        "channel": "route-us-a",
                        "status": "IDLE",
                        "attributes": {
                          "country": "US",
                          "carrier": "tmobile"
                        }
                      }
                    },
                    {
                      "MODEL": "WorkerContext",
                      "COUNT": 1,
                      "FIELDS": {
                        "workerContextId": "wc-us-1-b",
                        "workerId": "worker-us-1",
                        "channel": "route-us-b",
                        "status": "IDLE",
                        "attributes": {
                          "country": "US",
                          "carrier": "verizon"
                        }
                      }
                    }
                  ]
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private EngineConfig emptyRuleConfig(RuleManager<Map<String, Object>> ruleManager) {
        EngineConfig engineConfig = new EngineConfig();
        engineConfig.setRuleManager(ruleManager);
        engineConfig.setMockConfigRoot(JsonParser.parseString("""
                {
                  "rules": []
                }
                """).getAsJsonObject());
        return engineConfig;
    }

    private record TestHarness(MassEngine engine,
                               WorkerManager workerManager,
                               AtomicInteger createdTasks,
                               RuleManager<Map<String, Object>> ruleManager) {
    }
}
