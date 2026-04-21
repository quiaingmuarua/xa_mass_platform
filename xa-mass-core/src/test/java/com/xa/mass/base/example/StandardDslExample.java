package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;

import java.util.List;
import java.util.Map;

/**
 * Examples that separate the canonical typed path from the legacy/mock path.
 */
public class StandardDslExample {

    public static void main(String[] args) {
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("Task", Task.class);

        System.out.println("=== JSON DSL Path Examples ===\n");

        useCanonicalTypedDsl();
        useLegacyMockDsl();
        useCanonicalTypedJsonRoundTrip();
    }

    private static void useCanonicalTypedDsl() {
        System.out.println("--- Example 1: Canonical typed DSL ---");

        String typedDsl = """
                {
                  "uniqueId": "worker-generator-001",
                  "type": "generate",
                  "priority": 1,
                  "description": "Generate mock workers through the typed processor path",
                  "version": "1.0",
                  "author": "test_user",
                  "tags": ["worker", "test", "typed"],
                  "enabled": true,
                  "context": {
                    "model": "com.xa.mass.base.model.Worker",
                    "count": 3,
                    "scopeName": "Worker",
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
                    }
                  },
                  "combineDsl": {
                    "statusGroupLabel": "status == 'ONLINE' ? workerGroupId : 'unknown'"
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(typedDsl);
        List<Worker> workers = JsonDslProcessorEngine.process(
                definition,
                new ProcessingContext("standard-typed-example"),
                Worker.class
        );

        System.out.println("Parsed typed DSL:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  Type: " + definition.getType());
        System.out.println("  Description: " + definition.getDescription());
        System.out.println("  Scope: " + definition.getContext().getScopeName());
        System.out.println("Generated workers:");
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ", " + worker.getWorkerGroupId() + ")")
        );
        System.out.println();
    }

    private static void useLegacyMockDsl() {
        System.out.println("--- Example 2: Legacy/mock compatibility DSL ---");

        String legacyDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 2,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "taskRoutingCode": {"$CHOICE": ["us", "gb"]},
                    "taskTargetNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;

        List<Task> tasks = JsonDslEngine.generateList(legacyDsl, Task.class);
        System.out.println("Legacy/mock generation still works through JsonDslEngine:");
        tasks.forEach(task ->
                System.out.println("  - " + task.getTaskName() + " (" + task.getTaskRoutingCode() + ", batch: " + task.getBatchSize() + ")")
        );
        System.out.println();
    }

    private static void useCanonicalTypedJsonRoundTrip() {
        System.out.println("--- Example 3: Typed model round-trip ---");

        JsonDslDefinition definition = new JsonDslDefinition("compatibility-test-001", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Round-trip typed DSL");
        definition.setAuthor("compatibility_tester");
        definition.setTags(new String[]{"typed", "round-trip"});
        definition.setEnabled(true);

        JsonDslContext context = new JsonDslContext(Worker.class.getName(), 1);
        context.setScopeName("Worker");
        definition.setContext(context);
        definition.setFieldDsl(Map.of(
                "workerId", "test-worker-001",
                "status", "ONLINE",
                "workerGroupId", "test"
        ));

        String typedJson = JsonDslParser.toJson(definition);
        JsonDslDefinition parsedDefinition = JsonDslParser.parse(typedJson);
        List<Worker> workers = JsonDslProcessorEngine.process(
                parsedDefinition,
                new ProcessingContext("standard-round-trip-example"),
                Worker.class
        );

        System.out.println("Typed JSON:");
        System.out.println(typedJson);
        System.out.println("Parsed again:");
        System.out.println("  ID: " + parsedDefinition.getUniqueId());
        System.out.println("  Type: " + parsedDefinition.getType());
        System.out.println("  Description: " + parsedDefinition.getDescription());
        System.out.println("Generated verification data:");
        workers.forEach(worker ->
                System.out.println("  - " + worker.getWorkerId() + " (" + worker.getStatus() + ")")
        );
        System.out.println();
    }
}
