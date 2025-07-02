package com.xa.mass.base.mock;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import java.util.List;

public class MockExample {
    public static void main(String[] args) {
        // 注册类型
        MockTypeRegistry.register("Device", Device.class);
        MockTypeRegistry.register("Task", Task.class);
//        try {
//            Class<?> ruleDefClass = Class.forName("com.xa.mass.engine.rules.RuleDefinition");
//            MockTypeRegistry.register("RuleDefinition", ruleDefClass);
//        } catch (ClassNotFoundException e) {
//            throw new RuntimeException("RuleDefinition class not found", e);
//        }

        // 批量 mock Device
        String deviceDsl = """
        {
          "MODEL": "Device",
          "COUNT": 3,
          "FIELDS": {
            "deviceId": {"$JOIN": ["device-", "{i}"]},
            "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
            "groupId": {"$CHOICE": ["us", "gb", "cn"]},
            "agentVersion": {"$JOIN": ["1.0.", "{i}"]}
          }
        }
        """;
        List<Object> devices = MockTemplateEngine.generate(deviceDsl);
        devices.forEach(System.out::println);

        // 批量 mock Task
        String taskDsl = """
        {
          "MODEL": "Task",
          "COUNT": 2,
          "FIELDS": {
            "tid": {"$UUID": true},
            "taskName": {"$JOIN": ["Task-", "{i}"]},
            "taskCountry": {"$CHOICE": ["us", "gb"]},
            "taskInitNumber": {"$RANGE": [10, 100]},
            "batchSize": {"$RANGE": [1, 5]}
          }
        }
        """;
        List<Object> tasks = MockTemplateEngine.generate(taskDsl);
        tasks.forEach(System.out::println);

        // 批量 mock RuleDefinition
        String ruleDsl = """
        {
          "MODEL": "RuleDefinition",
          "COUNT": 2,
          "FIELDS": {
            "id": {"$UUID": true},
            "name": {"$JOIN": ["Rule-", "{i}"]},
            "description": {"$JOIN": ["desc-", "{i}"]},
            "content": {"$JOIN": ["device.groupId == 'us' || device.groupId == 'gb' // ", "{i}"]},
            "priority": {"$RANGE": [1, 10]},
            "enabled": {"$CHOICE": [true, false]}
          }
        }
        """;
//        List<Object> rules = MockTemplateEngine.generate(ruleDsl);
//        rules.forEach(System.out::println);
    }
} 