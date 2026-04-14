package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

public class JsonDslExample {
    public static void main(String[] args) {
        // 娉ㄥ唽绫诲瀷
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);
//        try {
//            Class<?> ruleDefClass = Class.forName("com.xa.mass.engine.rules.RuleDefinition");
//            TypeRegistry.register("RuleDefinition", ruleDefClass);
//        } catch (ClassNotFoundException e) {
//            throw new RuntimeException("RuleDefinition class not found", e);
//        }

        // 鎵归噺 mock Device - 浣跨敤 &.index 绠€鍐?
        String deviceDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 3,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "deviceGroupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]}
                  }
                }
                """;
        List<Device> devices = JsonDslEngine.generateList(deviceDsl, Device.class);
        System.out.println("=== Generated Devices ===");
        devices.forEach(System.out::println);

        // 鎵归噺 mock Task - 浣跨敤 &.index 绠€鍐?
        String taskDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 10,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "taskRoutingCountryCode": {"$CHOICE": ["us", "gb"]},
        "taskTargetNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;
        List<Task> tasks = JsonDslEngine.generateList(taskDsl, Task.class);
        System.out.println("\n=== Generated Tasks ===");
        tasks.forEach(System.out::println);

        // 澶氱骇浣滅敤鍩熷彉閲忔煡鎵剧ず渚嬶紝&.index 鍜?&Model.index 娣风敤
        String nestedExampleDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 2,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "deviceGroupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["1.0.", "&.index"]},
                    "description": {"$JOIN": ["Device ", "&.index", " in group ", "&Device.deviceGroupId"]},
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
        List<Device> devices1 = JsonDslEngine.generateList(nestedExampleDsl, Device.class);
        System.out.println("\n=== Nested Scope Variable Examples ===");
        devices1.forEach(System.out::println);

        // 婕旂ず鏃堕棿鍑芥暟
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

        // 婕旂ず鐩稿鏃堕棿
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
        List<Device> relativeTimeExamples = JsonDslEngine.generateList(relativeTimeExampleDsl, Device.class);
        System.out.println("\n=== Relative Time Examples ===");
        relativeTimeExamples.forEach(System.out::println);
    }
}
