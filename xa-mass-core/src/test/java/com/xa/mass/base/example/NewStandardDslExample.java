package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Worker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Example usage for the new standard DSL without manual type registration.
 */
public class NewStandardDslExample {

    public static void main(String[] args) {
        System.out.println("=== New Standard DSL Example ===\n");

        example1BasicGenerateDsl();
        example2ComplexGenerateDsl();
        example3FilterDsl();
        example4TransformDsl();
        example5ValidateDsl();
        example6ParseFromJson();
    }

    private static void example1BasicGenerateDsl() {
        System.out.println("--- Example 1: Basic generate DSL ---");

        JsonDslDefinition definition = new JsonDslDefinition("basic_worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate a few basic workers");
        definition.setAuthor("system");
        definition.setTags(new String[]{"worker", "basic"});
        definition.setPriority(1);

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Worker", 3);
        context.setScopeName("Worker");
        context.setDebug(true);
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("worker-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("workerGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        definition.setFieldDsl(fieldDsl);

        definition.validate();

        String legacyFormat = JsonDslParser.toJson(definition);
        List<Worker> workers = JsonDslEngine.generateList(legacyFormat, Worker.class);

        System.out.println("Generated workers: " + workers.size());
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")"));
        System.out.println();
    }

    private static void example2ComplexGenerateDsl() {
        System.out.println("--- Example 2: Complex generate DSL ---");

        JsonDslDefinition definition = new JsonDslDefinition("complex_worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate nested worker structures");
        definition.setAuthor("advanced_user");
        definition.setTags(new String[]{"worker", "complex", "nested"});
        definition.setPriority(2);

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Worker", 2);
        context.setScopeName("Worker");
        context.setDebug(true);
        context.setStrict(true);
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("complex-worker-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("workerGroupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        fieldDsl.put("agentVersion", Map.of("$JOIN", Arrays.asList("2.0.", "&.index")));

        Map<String, Object> tasksField = new HashMap<>();
        tasksField.put("TYPE", "LIST");
        tasksField.put("COUNT", 2);
        tasksField.put("MODEL", "com.xa.mass.base.model.Task");

        Map<String, Object> taskFields = new HashMap<>();
        taskFields.put("tid", Map.of("$UUID", true));
        taskFields.put("taskName", Map.of("$JOIN", Arrays.asList("ComplexTask-", "&.index", "-of-Worker-", "&Worker.index")));
        taskFields.put("taskRoutingCountryCode", "&Worker.workerGroupId");
        taskFields.put("taskTargetNumber", Map.of("$RANGE", Arrays.asList(50, 200)));
        taskFields.put("batchSize", Map.of("$RANGE", Arrays.asList(2, 8)));
        tasksField.put("FIELDS", taskFields);
        fieldDsl.put("tasks", tasksField);

        Map<String, Object> onlineStrategy = new HashMap<>();
        onlineStrategy.put("$EXPR", Map.of(
                "lang", "ql",
                "expr", "status == 'OFFLINE' ? 0 : range(10, 100)"
        ));
        fieldDsl.put("onlineStrategy", onlineStrategy);
        definition.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("worker_task_balance", "tasks.size() <= 3 ? 'balanced' : 'overloaded'");
        combineDsl.put("status_performance", "status == 'ONLINE' && agentVersion.startsWith('2.0') ? 'high_performance' : 'standard'");
        combineDsl.put("group_capacity", "workerGroupId == 'us' ? 100 : workerGroupId == 'gb' ? 50 : 30");
        definition.setCombineDsl(combineDsl);

        definition.validate();
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Worker> workers = JsonDslEngine.generateList(legacyFormat, Worker.class);

        System.out.println("Generated complex workers: " + workers.size());
        workers.forEach(worker -> {
            System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")");
            System.out.println("    onlineStrategy: " + worker.getOnlineStrategy());
        });
        System.out.println();
    }

    private static void example3FilterDsl() {
        System.out.println("--- Example 3: Filter DSL ---");

        JsonDslDefinition filterDef = new JsonDslDefinition("online_worker_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("Filter online workers");
        filterDef.setAuthor("system");
        filterDef.setPriority(10);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        fieldDsl.put("workerGroupId", Map.of("$in", Arrays.asList("us", "gb")));
        filterDef.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("battery_check", "batteryLevel >= 20");
        combineDsl.put("signal_check", "signalStrength >= 50");
        filterDef.setCombineDsl(combineDsl);

        filterDef.validate();
        System.out.println(JsonDslParser.toJson(filterDef));
        System.out.println();
    }

    private static void example4TransformDsl() {
        System.out.println("--- Example 4: Transform DSL ---");

        JsonDslDefinition transformDef = new JsonDslDefinition("worker_transformer", JsonDslDefinition.DslType.TRANSFORM);
        transformDef.setDescription("Transform worker fields");
        transformDef.setAuthor("system");
        transformDef.setPriority(5);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$UPPER", "&.workerId"));
        fieldDsl.put("status", Map.of("$MAP", Map.of("ONLINE", "active", "OFFLINE", "inactive")));
        fieldDsl.put("workerGroupId", Map.of("$UPPER", "&.workerGroupId"));
        transformDef.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("full_name", "workerId + '_' + workerGroupId");
        combineDsl.put("status_code", "status == 'active' ? 1 : 0");
        transformDef.setCombineDsl(combineDsl);

        transformDef.validate();
        System.out.println("Transform DSL created.");
        System.out.println();
    }

    private static void example5ValidateDsl() {
        System.out.println("--- Example 5: Validate DSL ---");

        JsonDslDefinition validateDef = new JsonDslDefinition("worker_validator", JsonDslDefinition.DslType.VALIDATE);
        validateDef.setDescription("Validate worker records");
        validateDef.setAuthor("system");
        validateDef.setPriority(1);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("required", true, "pattern", "^worker-\\d+$"));
        fieldDsl.put("status", Map.of("enum", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("workerGroupId", Map.of("required", true, "minLength", 2, "maxLength", 10));
        validateDef.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("status_consistency", "status == 'ONLINE' ? batteryLevel > 0 : true");
        combineDsl.put("group_validity", "workerGroupId in ['us', 'gb', 'cn', 'eu']");
        validateDef.setCombineDsl(combineDsl);

        validateDef.validate();
        System.out.println("Validate DSL created.");
        System.out.println();
    }

    private static void example6ParseFromJson() {
        System.out.println("--- Example 6: Parse from JSON ---");

        String jsonDsl = """
                {
                  "unique_id": "json_worker_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "Generate workers from JSON",
                  "version": "1.0",
                  "author": "json_user",
                  "tags": ["json", "worker"],
                  "context": {
                    "MODEL": "com.xa.mass.base.model.Worker",
                    "COUNT": 2,
                    "scope_name": "Worker",
                    "debug": true
                  },
                  "fieldDsl": {
                    "workerId": {"$JOIN": ["json-worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "workerGroupId": {"$CHOICE": ["us", "gb"]}
                  },
                  "combine_dsl": {
                    "status_group": "status == 'ONLINE' ? workerGroupId : 'unknown'"
                  },
                  "extensions": {
                    "source": "json_parser"
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);
        System.out.println("DSL ID: " + definition.getUniqueId());
        System.out.println("DSL type: " + definition.getType());
        System.out.println("Description: " + definition.getDescription());

        String legacyFormat = JsonDslParser.toJson(definition);
        List<Worker> workers = JsonDslEngine.generateList(legacyFormat, Worker.class);
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")"));
        System.out.println();
    }
}
