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
        List<Object> devices = JsonDslEngine.generate(deviceDsl);
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
        List<Object> tasks = JsonDslEngine.generate(taskDsl);
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
        List<Object> nestedExamples = JsonDslEngine.generate(nestedExampleDsl);
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
        List<Object> timeExamples = JsonDslEngine.generate(timeExampleDsl);
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
        List<Object> relativeTimeExamples = JsonDslEngine.generate(relativeTimeExampleDsl);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);
    }
} 