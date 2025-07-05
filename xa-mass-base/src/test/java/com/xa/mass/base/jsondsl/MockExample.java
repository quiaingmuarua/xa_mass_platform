package com.xa.mass.base.jsondsl;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class MockExample {
    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);
//        try {
//            Class<?> ruleDefClass = Class.forName("com.xa.mass.engine.rules.RuleDefinition");
//            MockTypeRegistry.register("RuleDefinition", ruleDefClass);
//        } catch (ClassNotFoundException e) {
//            throw new RuntimeException("RuleDefinition class not found", e);
//        }

        // 批量 mock Device - 使用 $CONTEXT 函数
        String deviceDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 3,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", {"$CONTEXT": "i"}]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", {"$CONTEXT": "i"}]}
                  }
                }
                """;
        Object result = JsonDslEngine.generate(deviceDsl);
        List<Object> devices = result instanceof List ? (List<Object>) result : List.of(result);
        System.out.println("=== Generated Devices ===");
        devices.forEach(System.out::println);

        // 批量 mock Task - 使用 $CONTEXT 函数
        String taskDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 2,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", {"$CONTEXT": "i"}]},
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

        // 演示 $CONTEXT 函数的多种用法
        String contextExampleDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 5,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", {"$CONTEXT": "i"}]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", {"$CONTEXT": "i"}]},
                    "onlineStrategy": {"$JOIN": ["Device ", {"$CONTEXT": "i"}, " in group ", {"$CONTEXT": "groupId"}]},
                    "createTime":{"$TIME_RANGE": ["now-30d", "now-1d", "DAYS", "yyyy-MM-dd"]}
                  }
                }
                """;
        Object result3 = JsonDslEngine.generate(contextExampleDsl);
        List<Object> contextExamples = result3 instanceof List ? (List<Object>) result3 : List.of(result3);
        System.out.println("\n=== Context Function Examples ===");
        contextExamples.forEach(System.out::println);


    }
} 