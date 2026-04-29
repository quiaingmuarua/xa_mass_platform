package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.storage.rule.RuleDefinition;

import java.util.List;

/**
 * Dev/mock fixture generator backed by the legacy JSON-DSL object generator.
 *
 * <p>This class is for demo data and local runtime bootstrap only. Worker
 * matching uses {@code RuleDefinition + QLExpressRuleEvaluator +
 * WorkerMatchContext}; do not route assignment or binding decisions through
 * this generator.
 */
@SuppressWarnings("deprecation")
public class MonkeyGenerator {

    static {
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("WorkerContext", WorkerContext.class);
        TypeRegistry.register("RuleDefinition", RuleDefinition.class);
        TypeRegistry.register("TaskCreateRequestDto", TaskCreateRequestDto.class);
    }

    public static List<Worker> generateWorkers(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, Worker.class);
    }

    public static List<WorkerContext> generateWorkerContexts(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, WorkerContext.class);
    }

    public static List<TaskCreateRequestDto> generateTasks(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, TaskCreateRequestDto.class);
    }

    public static List<RuleDefinition> generateRules(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, RuleDefinition.class);
    }

    public static String exampleTasksJsonDsl() {
        return """
                {
                  "MODEL": "TaskCreateRequestDto",
                  "COUNT": 2,
                  "FIELDS": {
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "project": {"$CHOICE": ["demoApp", "testApp"]},
                    "userId": {"$JOIN": ["user-", "&.index"]},
                    "sharedConfig": {
                      "textContent": {"$JOIN": ["content for ", "&.index"]},
                      "routingCode": {"$CHOICE": ["us", "gb"]}
                    },
                    "batchSize": {"$RANGE": [1, 5]},
                    "inputs": {
                      "TYPE": "LIST",
                      "COUNT": 3,
                      "MODEL": "java.util.LinkedHashMap",
                      "FIELDS": {
                        "target": {"$JOIN": ["target-", "&.index"]}
                      }
                    }
                  }
                }
                """;
    }

    public static String exampleJsonDsl() {
        return """
                {
                  "MODEL": "Worker",
                  "COUNT": 3,
                  "FIELDS": {
                    "workerId": {"$JOIN": ["worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "workerGroupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]}
                  }
                }
                """;
    }
}
