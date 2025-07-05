package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslExample {
    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);
//        try {
//            Class<?> ruleDefClass = Class.forName("com.xa.mass.engine.rules.RuleDefinition");
//            TypeRegistry.register("RuleDefinition", ruleDefClass);
//        } catch (ClassNotFoundException e) {
//            throw new RuntimeException("RuleDefinition class not found", e);
//        }

        // 批量 mock Device - 使用 &.index 简写
        String deviceDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 3,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]}
                  }
                }
                """;
        Object result = JsonDslEngine.generate(deviceDsl);
        List<Object> devices = result instanceof List ? (List<Object>) result : List.of(result);
        System.out.println("=== Generated Devices ===");
        devices.forEach(System.out::println);

        // 批量 mock Task - 使用 &.index 简写
        String taskDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 10,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "taskCountry": {"$CHOICE": ["us", "gb"]},
                    "taskInitNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;
        Object result2 = JsonDslEngine.generate(taskDsl);
        List<Object> tasks = result2 instanceof List ? (List<Object>) result2 : List.of(result2);
        System.out.println("\n=== Generated Tasks ===");
        tasks.forEach(System.out::println);

        // 多级作用域变量查找示例，&.index 和 &Model.index 混用
        String nestedExampleDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 2,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]},
                    "description": {"$JOIN": ["Device ", "&.index", " in group ", "&Device.groupId"]},
                    "tasks": {
                      "TYPE": "LIST",
                      "COUNT": 2,
                      "MODEL": "Task",
                      "FIELDS": {
                        "tid": {"$UUID": true},
                        "taskName": {"$JOIN": ["Task-", "&.index", "-of-Device-", "&Device.index"]},
                        "parentDeviceId": "&Device.deviceId"
                      }
                    }
                  }
                }
                """;
        Object result3 = JsonDslEngine.generate(nestedExampleDsl);
        List<Object> nestedExamples = result3 instanceof List ? (List<Object>) result3 : List.of(result3);
        System.out.println("\n=== Nested Scope Variable Examples ===");
        nestedExamples.forEach(System.out::println);

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
        Object result4 = JsonDslEngine.generate(timeExampleDsl);
        List<Object> timeExamples = result4 instanceof List ? (List<Object>) result4 : List.of(result4);
        System.out.println("\n=== Time Function Examples ===");
        timeExamples.forEach(System.out::println);

        // 演示相对时间
        String relativeTimeExampleDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 2,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"}
                  }
                }
                """;
        Object result5 = JsonDslEngine.generate(relativeTimeExampleDsl);
        List<Object> relativeTimeExamples = result5 instanceof List ? (List<Object>) result5 : List.of(result5);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);
    }
} 