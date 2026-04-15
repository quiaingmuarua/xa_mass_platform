package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * Example usage for the standard JSON-DSL format.
 */
public class StandardDslExample {

    public static void main(String[] args) {
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("Task", Task.class);

        System.out.println("=== Standard JSON-DSL Examples ===\n");

        useStandardDslFormat();
        useLegacyDslFormat();
        useComplexCombinedDsl();
        demonstrateCompatibility();
    }

    private static void useStandardDslFormat() {
        System.out.println("--- Example 1: Standard DSL format ---");

        String standardDsl = """
                {
                  "unique_id": "worker_generator_001",
                  "type": "generate",
                  "priority": 1,
                  "desc": "Generate mock workers",
                  "version": "1.0",
                  "author": "test_user",
                  "tags": ["worker", "test", "mock"],
                  "enabled": true,
                  "cacheable": true,
                  "cache_expire_seconds": 600,
                  "context": {
                    "MODEL": "Worker",
                    "COUNT": 3,
                    "scope_name": "Worker",
                    "debug": false,
                    "strict": true
                  },
                  "fieldDsl": {
                    "workerId": {
                      "$JOIN": ["worker-", "&.index"]
                    },
                    "status": {
                      "$CHOICE": ["ONLINE", "OFFLINE"]
                    },
                    "workerGroupId": {
                      "$CHOICE": ["us", "gb", "cn"]
                    },
                    "agentVersion": {
                      "$JOIN": ["1.0.", "&.index"]
                    },
                    "supportedProjects": ["demoApp", "otherApp", "testApp"]
                  },
                  "combine_dsl": {
                    "status_group_rule": "status == 'ONLINE' ? workerGroupId : 'unknown'",
                    "version_check_rule": "agentVersion.startsWith('1.0') ? 'stable' : 'beta'"
                  },
                  "extensions": {
                    "metadata": {
                      "source": "test_data",
                      "environment": "dev"
                    }
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(standardDsl);
        System.out.println("Parsed DSL definition:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  Type: " + definition.getType());
        System.out.println("  Description: " + definition.getDescription());
        System.out.println("  Author: " + definition.getAuthor());

        String legacyFormat = JsonDslParser.toJson(definition);
        List<Worker> workers = JsonDslEngine.generateList(legacyFormat, Worker.class);

        System.out.println("Generated workers:");
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")")
        );
        System.out.println();
    }

    private static void useLegacyDslFormat() {
        System.out.println("--- Example 2: Legacy DSL format ---");

        String legacyDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 2,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "taskRoutingCountryCode": {"$CHOICE": ["us", "gb"]},
                    "taskTargetNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(legacyDsl);
        System.out.println("Parsed legacy DSL:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  Type: " + definition.getType());
        System.out.println("  Description: " + definition.getDescription());

        List<Task> tasks = JsonDslEngine.generateList(legacyDsl, Task.class);
        System.out.println("Generated tasks:");
        tasks.forEach(task ->
                System.out.println("  - " + task.getTaskName() + " (" +
                        task.getTaskRoutingCountryCode() + ", batch: " + task.getBatchSize() + ")")
        );
        System.out.println();
    }

    private static void useComplexCombinedDsl() {
        System.out.println("--- Example 3: Complex combined DSL ---");

        String complexDsl = """
                {
                  "unique_id": "complex_worker_task_001",
                  "type": "generate",
                  "priority": 2,
                  "desc": "Generate workers with nested tasks",
                  "author": "advanced_user",
                  "tags": ["complex", "nested", "advanced"],
                  "context": {
                    "MODEL": "Worker",
                    "COUNT": 2,
                    "scope_name": "Worker",
                    "debug": true
                  },
                  "fieldDsl": {
                    "workerId": {"$JOIN": ["complex-worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "workerGroupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["2.0.", "&.index"]},
                    "tasks": {
                      "TYPE": "LIST",
                      "COUNT": 2,
                      "MODEL": "Task",
                      "FIELDS": {
                        "tid": {"$UUID": true},
                        "taskName": {"$JOIN": ["ComplexTask-", "&.index", "-of-Worker-", "&Worker.index"]},
                        "taskRoutingCountryCode": "&Worker.workerGroupId",
                        "taskTargetNumber": {"$RANGE": [50, 200]},
                        "batchSize": {"$RANGE": [2, 8]}
                      }
                    }
                  },
                  "combine_dsl": {
                    "worker_task_balance": "tasks.size() <= 3 ? 'balanced' : 'overloaded'",
                    "status_performance": "status == 'ONLINE' && agentVersion.startsWith('2.0') ? 'high_performance' : 'standard'",
                    "group_capacity": "workerGroupId == 'us' ? 100 : workerGroupId == 'gb' ? 50 : 30"
                  },
                  "extensions": {
                    "business_rules": {
                      "max_tasks_per_worker": 5,
                      "preferred_groups": ["us", "gb"],
                      "performance_threshold": 0.8
                    }
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(complexDsl);
        System.out.println("Complex DSL definition:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  Priority: " + definition.getPriority());
        System.out.println("  Debug: " + definition.getContext().getDebug());
        System.out.println("  Combine rules: " + definition.getCombineDsl().size());

        String legacyFormat = JsonDslParser.toJson(definition);
        List<Worker> workers = JsonDslEngine.generateList(legacyFormat, Worker.class);

        System.out.println("Generated complex workers:");
        workers.forEach(worker -> {
            System.out.println("  - Worker: " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")");
            System.out.println("    Agent version: " + worker.getAgentVersion());
        });
        System.out.println();
    }

    private static void demonstrateCompatibility() {
        System.out.println("--- Example 4: Compatibility ---");

        JsonDslDefinition definition = new JsonDslDefinition("compatibility_test_001", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Compatibility test DSL");
        definition.setAuthor("compatibility_tester");
        definition.setTags(new String[]{"test", "compatibility"});
        definition.setEnabled(true);

        JsonDslContext context = new JsonDslContext("Worker", 1);
        context.setScopeName("Worker");
        definition.setContext(context);

        definition.setFieldDsl(java.util.Map.of(
                "workerId", "test-worker-001",
                "status", "ONLINE",
                "workerGroupId", "test"
        ));

        String json = JsonDslParser.toJson(definition);
        String legacyJson = JsonDslParser.toJson(definition);

        System.out.println("Standard DSL JSON:");
        System.out.println(json);
        System.out.println("Legacy JSON:");
        System.out.println(legacyJson);

        JsonDslDefinition parsedDefinition = JsonDslParser.parse(json);
        System.out.println("Parsed again:");
        System.out.println("  ID: " + parsedDefinition.getUniqueId());
        System.out.println("  Type: " + parsedDefinition.getType());
        System.out.println("  Description: " + parsedDefinition.getDescription());

        List<Worker> workers = JsonDslEngine.generateList(legacyJson, Worker.class);
        System.out.println("Generated verification data:");
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ")")
        );
        System.out.println();
    }
}
