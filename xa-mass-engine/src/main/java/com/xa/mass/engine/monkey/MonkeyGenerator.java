package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleDefinition;

import java.util.List;

/**
 * 基于 JSON-DSL 的 mock Worker/WorkerContext 生成器。
 */
public class MonkeyGenerator {

    static {
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("WorkerContext", WorkerContext.class);
        TypeRegistry.register("RuleDefinition", com.xa.mass.engine.rules.RuleDefinition.class);
        TypeRegistry.register("TaskCreateRequestDto", TaskCreateRequestDto.class);
    }

    /**
     * 根据 JSON-DSL 生成 Worker 列表（支持递归嵌套 WorkerContext）。
     */
    public static List<Worker> generateWorkers(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, Worker.class);
    }

    /**
     * 根据 JSON-DSL 生成 WorkerContext 列表。
     */
    public static List<WorkerContext> generateWorkerContexts(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, WorkerContext.class);
    }

    /**
     * 根据 JSON-DSL 生成 TaskCreateRequestDto 列表。
     */
    public static List<TaskCreateRequestDto> generateTasks(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, TaskCreateRequestDto.class);
    }

    /**
     * Generates rule definitions from JSON-DSL.
     */
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
                    "countryCode": {"$CHOICE": ["us", "gb"]},
                    "userId": {"$JOIN": ["user-", "&.index"]},
                    "sharedConfig": {"textContent": {"$JOIN": ["content for ", "&.index"]}},
                    "batchSize": {"$RANGE": [1, 5]},
                    "targetList": {
                      "TYPE": "LIST",
                      "COUNT": 3,
                      "MODEL": "java.lang.String",
                      "FIELDS": {}
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
