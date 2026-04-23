package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslExample {
    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Worker", Worker.class);
        TypeRegistry.register("Task", Task.class);

        // 批量 mock Worker
        String workerDsl = """
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
        List<Worker> workers = JsonDslEngine.generateList(workerDsl, Worker.class);
        System.out.println("=== Generated Workers ===");
        workers.forEach(System.out::println);

        // 批量 mock Task
        String taskDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 10,
                    "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
        "taskTargetNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;
        List<Task> tasks = JsonDslEngine.generateList(taskDsl, Task.class);
        System.out.println("\n=== Generated Tasks ===");
        tasks.forEach(System.out::println);

        // 多级作用域变量查找示例，&.index 和 &Model.index 混用
        String nestedExampleDsl = """
                {
                  "MODEL": "Worker",
                  "COUNT": 2,
                  "FIELDS": {
                    "workerId": {"$JOIN": ["worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "workerGroupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]},
                    "description": {"$JOIN": ["Worker ", "&.index", " in group ", "&Worker.workerGroupId"]},
                    "tasks": {
                      "TYPE": "LIST",
                      "COUNT": 2,
                      "MODEL": "Task",
                      "FIELDS": {
                        "tid": {"$UUID": true},
                        "taskName": {"$JOIN": ["Task-", "&.index", "-of-Worker-", "&Worker.index"]},
                        "parentWorkerId": "&Worker.workerId"
                      }
                    }
                  }
                }
                """;
        List<Worker> workers1 = JsonDslEngine.generateList(nestedExampleDsl, Worker.class);
        System.out.println("\n=== Nested Scope Variable Examples ===");
        workers1.forEach(System.out::println);

        // 演示时间函数
        String timeExampleDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 3,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["TimeTask-", "&.index"]},
                    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"},
                    "lastModified": {"$TIME_RANGE": ["now-2h", "now", "MINUTES"]}
                  }
                }
                """;
        List<Task> timeExamples = JsonDslEngine.generateList(timeExampleDsl, Task.class);
        System.out.println("\n=== Time Function Examples ===");
        timeExamples.forEach(System.out::println);

        // 演示相对时间
        String relativeTimeExampleDsl = """
                {
                  "MODEL": "Worker",
                  "COUNT": 2,
                  "FIELDS": {
                    "workerId": {"$JOIN": ["worker-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"}
                  }
                }
                """;
        List<Worker> relativeTimeExamples = JsonDslEngine.generateList(relativeTimeExampleDsl, Worker.class);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);
    }
}
