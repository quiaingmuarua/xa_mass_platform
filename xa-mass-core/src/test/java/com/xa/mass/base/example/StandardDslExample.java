package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * 标准化 JSON-DSL 使用示例
 * <p>
 * 展示新旧格式的对比和使用方法
 * </p>
 */
public class StandardDslExample {

    public static void main(String[] args) {
        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Task", Task.class);

        System.out.println("=== 标准化 JSON-DSL 示例 ===\n");

        // 示例1: 使用新的标准化 DSL 格式
        useStandardDslFormat();

        // 示例2: 向后兼容传统 DSL 格式
        useLegacyDslFormat();

        // 示例3: 复杂组合 DSL
        useComplexCombinedDsl();

        // 示例4: DSL 转换和兼容性
        demonstrateCompatibility();
    }

    /**
     * 示例1: 使用新的标准化 DSL 格式
     */
    private static void useStandardDslFormat() {
        System.out.println("--- 示例1: 标准化 DSL 格式 ---");

        // 新的标准化 DSL 格式
        String standardDsl = """
                {
                  "unique_id": "device_generator_001",
                  "type": "generate",
                  "priority": 1,
                  "desc": "生成测试设备数据",
                  "version": "1.0",
                  "author": "test_user",
                  "tags": ["device", "test", "mock"],
                  "enabled": true,
                  "cacheable": true,
                  "cache_expire_seconds": 600,
                  "context": {
                    "MODEL": "Device",
                    "COUNT": 3,
                    "scope_name": "Device",
                    "debug": false,
                    "strict": true
                  },
                  "fieldDsl": {
                    "deviceId": {
                      "$JOIN": ["device-", "&.index"]
                    },
                    "status": {
                      "$CHOICE": ["ONLINE", "OFFLINE"]
                    },
                    "groupId": {
                      "$CHOICE": ["us", "gb", "cn"]
                    },
                    "agentVersion": {
                      "$JOIN": ["1.0.", "&.index"]
                    },
                    "supportedProjects": ["demoApp", "otherApp", "testApp"]
                  },
                  "combine_dsl": {
                    "status_group_rule": "status == 'ONLINE' ? groupId : 'unknown'",
                    "version_check_rule": "agentVersion.startsWith('1.0') ? 'stable' : 'beta'"
                  },
                  "extensions": {
                    "metadata": {
                      "source": "test_data",
                      "environment": "dev"
                    }
                  }
                }
                """;

        // 解析标准化 DSL
        JsonDslDefinition definition = JsonDslParser.parse(standardDsl);
        System.out.println("解析的 DSL 定义:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  类型: " + definition.getType());
        System.out.println("  描述: " + definition.getDescription());
        System.out.println("  作者: " + definition.getAuthor());
        System.out.println("  标签: " + String.join(", ", definition.getTags()));
        System.out.println("  缓存: " + (definition.getCacheable() ? "启用" : "禁用"));

        // 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toJson(definition);
        System.out.println("\n转换为传统格式:");
        System.out.println(legacyFormat);

        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
        System.out.println("\n生成的设备:");
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getGroupId() + ")")
        );
        System.out.println();
    }

    /**
     * 示例2: 向后兼容传统 DSL 格式
     */
    private static void useLegacyDslFormat() {
        System.out.println("--- 示例2: 传统 DSL 格式（向后兼容） ---");

        // 传统 DSL 格式
        String legacyDsl = """
                {
                  "MODEL": "Task",
                  "COUNT": 2,
                  "FIELDS": {
                    "tid": {"$UUID": true},
                    "taskName": {"$JOIN": ["Task-", "&.index"]},
                    "taskCountry": {"$CHOICE": ["us", "gb"]},
                    "taskInitNumber": {"$RANGE": [10, 100]},
                    "batchSize": {"$RANGE": [1, 5]}
                  }
                }
                """;

        // 解析传统 DSL（会自动转换为标准化格式）
        JsonDslDefinition definition = JsonDslParser.parse(legacyDsl);
        System.out.println("传统 DSL 自动转换为标准化格式:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  类型: " + definition.getType());
        System.out.println("  描述: " + definition.getDescription());

        // 直接生成数据（向后兼容）
        List<Task> tasks = JsonDslEngine.generateList(legacyDsl, Task.class);
        System.out.println("\n生成的任务:");
        tasks.forEach(task ->
                System.out.println("  - " + task.getTaskName() + " (" + task.getTaskCountry() + ", 批次: " + task.getBatchSize() + ")")
        );
        System.out.println();
    }

    /**
     * 示例3: 复杂组合 DSL
     */
    private static void useComplexCombinedDsl() {
        System.out.println("--- 示例3: 复杂组合 DSL ---");

        // 包含组合规则的复杂 DSL
        String complexDsl = """
                {
                  "unique_id": "complex_device_task_001",
                  "type": "generate",
                  "priority": 2,
                  "desc": "生成包含嵌套任务的复杂设备数据",
                  "author": "advanced_user",
                  "tags": ["complex", "nested", "advanced"],
                  "context": {
                    "MODEL": "Device",
                    "COUNT": 2,
                    "scope_name": "Device",
                    "debug": true
                  },
                  "fieldDsl": {
                    "deviceId": {"$JOIN": ["complex-device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb", "cn"]},
                    "agentVersion": {"$JOIN": ["2.0.", "&.index"]},
                    "tasks": {
                      "TYPE": "LIST",
                      "COUNT": 2,
                      "MODEL": "Task",
                      "FIELDS": {
                        "tid": {"$UUID": true},
                        "taskName": {"$JOIN": ["ComplexTask-", "&.index", "-of-Device-", "&Device.index"]},
                        "taskCountry": "&Device.groupId",
                        "taskInitNumber": {"$RANGE": [50, 200]},
                        "batchSize": {"$RANGE": [2, 8]}
                      }
                    }
                  },
                  "combine_dsl": {
                    "device_task_balance": "tasks.size() <= 3 ? 'balanced' : 'overloaded'",
                    "status_performance": "status == 'ONLINE' && agentVersion.startsWith('2.0') ? 'high_performance' : 'standard'",
                    "group_capacity": "groupId == 'us' ? 100 : groupId == 'gb' ? 50 : 30"
                  },
                  "extensions": {
                    "business_rules": {
                      "max_tasks_per_device": 5,
                      "preferred_groups": ["us", "gb"],
                      "performance_threshold": 0.8
                    }
                  }
                }
                """;

        JsonDslDefinition definition = JsonDslParser.parse(complexDsl);
        System.out.println("复杂 DSL 定义:");
        System.out.println("  ID: " + definition.getUniqueId());
        System.out.println("  优先级: " + definition.getPriority());
        System.out.println("  调试模式: " + (definition.getContext().getDebug() ? "启用" : "禁用"));
        System.out.println("  组合规则数: " + definition.getCombineDsl().size());

        // 生成数据
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("\n生成的复杂设备:");
        devices.forEach(device -> {
            System.out.println("  - 设备: " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getGroupId() + ")");
            // 注意：Device 模型可能没有 tasks 字段，这里仅作示例
            System.out.println("    设备版本: " + device.getAgentVersion());
        });
        System.out.println();
    }

    /**
     * 示例4: DSL 转换和兼容性
     */
    private static void demonstrateCompatibility() {
        System.out.println("--- 示例4: DSL 转换和兼容性 ---");

        // 创建一个标准 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("compatibility_test_001", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("兼容性测试 DSL");
        definition.setAuthor("compatibility_tester");
        definition.setTags(new String[]{"test", "compatibility"});
        definition.setEnabled(true);

        // 设置上下文
        JsonDslContext context = new JsonDslContext("Device", 1);
        context.setScopeName("Device");
        definition.setContext(context);

        // 设置字段 DSL
        definition.setFieldDsl(java.util.Map.of(
                "deviceId", "test-device-001",
                "status", "ONLINE",
                "groupId", "test"
        ));

        // 转换为 JSON
        String json = JsonDslParser.toJson(definition);
        System.out.println("标准 DSL 定义 JSON:");
        System.out.println(json);

        // 转换为传统格式
        String legacyJson = JsonDslParser.toJson(definition);
        System.out.println("\n转换为传统格式:");
        System.out.println(legacyJson);

        // 重新解析并验证
        JsonDslDefinition parsedDefinition = JsonDslParser.parse(json);
        System.out.println("\n重新解析验证:");
        System.out.println("  ID: " + parsedDefinition.getUniqueId());
        System.out.println("  类型: " + parsedDefinition.getType());
        System.out.println("  描述: " + parsedDefinition.getDescription());

        // 生成数据验证
        List<Device> devices = JsonDslEngine.generateList(legacyJson, Device.class);
        System.out.println("\n生成验证:");
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ")")
        );
        System.out.println();
    }
} 