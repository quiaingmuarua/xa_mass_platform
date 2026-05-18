package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Worker;
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
        TypeRegistry.register("RuleDefinition", RuleDefinition.class);
        TypeRegistry.register("TaskFixture", TaskFixture.class);
    }

    public static List<Worker> generateWorkers(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, Worker.class);
    }

    public static List<TaskFixture> generateTasks(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, TaskFixture.class);
    }

    public static List<RuleDefinition> generateRules(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, RuleDefinition.class);
    }

    public static String exampleTasksJsonDsl() {
        return """
                {
                  "MODEL": "TaskFixture",
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

    public static final class TaskFixture {
        private String userId;
        private String project;
        private String taskName;
        private java.util.Map<String, Object> sharedConfig;
        private java.util.List<java.util.Map<String, Object>> inputs;
        private int batchSize;
        private int defaultMaxRetryCount = 3;
        private boolean sealIntakeAfterCreate;
        private int maxRuntimeSeconds;
        private TaskWorkloadClass workloadClass;
        private String sourceRef;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public String getTaskName() {
            return taskName;
        }

        public void setTaskName(String taskName) {
            this.taskName = taskName;
        }

        public java.util.Map<String, Object> getSharedConfig() {
            return sharedConfig;
        }

        public void setSharedConfig(java.util.Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig;
        }

        public java.util.List<java.util.Map<String, Object>> getInputs() {
            return inputs;
        }

        public void setInputs(java.util.List<java.util.Map<String, Object>> inputs) {
            this.inputs = inputs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getDefaultMaxRetryCount() {
            return defaultMaxRetryCount;
        }

        public void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
            this.defaultMaxRetryCount = defaultMaxRetryCount;
        }

        public boolean isSealIntakeAfterCreate() {
            return sealIntakeAfterCreate;
        }

        public void setSealIntakeAfterCreate(boolean sealIntakeAfterCreate) {
            this.sealIntakeAfterCreate = sealIntakeAfterCreate;
        }

        public int getMaxRuntimeSeconds() {
            return maxRuntimeSeconds;
        }

        public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
        }

        public TaskWorkloadClass getWorkloadClass() {
            return workloadClass;
        }

        public void setWorkloadClass(TaskWorkloadClass workloadClass) {
            this.workloadClass = workloadClass;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
        }
    }
}


