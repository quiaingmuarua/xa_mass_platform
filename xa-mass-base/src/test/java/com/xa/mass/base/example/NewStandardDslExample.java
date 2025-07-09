package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.model.Device;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 新标准 DSL 使用示例
 * <p>
 * 展示如何使用新的标准化 DSL 结构替代旧的过时方法
 * 新标准 DSL 系统支持直接使用全类名，无需提前注册类型
 * </p>
 */
public class NewStandardDslExample {

    public static void main(String[] args) {
        System.out.println("=== 新标准 DSL 使用示例 ===\n");
        System.out.println("注意：新标准 DSL 系统支持直接使用全类名，无需提前注册类型！\n");

        // 示例1: 基本生成 DSL（使用全类名）
        example1_BasicGenerateDsl();

        // 示例2: 复杂生成 DSL（使用全类名）
        example2_ComplexGenerateDsl();

        // 示例3: 过滤器 DSL
        example3_FilterDsl();

        // 示例4: 转换 DSL
        example4_TransformDsl();

        // 示例5: 验证 DSL
        example5_ValidateDsl();

        // 示例6: 从 JSON 解析 DSL（使用全类名）
        example6_ParseFromJson();
    }

    /**
     * 示例1: 基本生成 DSL（使用全类名，无需注册）
     */
    private static void example1_BasicGenerateDsl() {
        System.out.println("--- 示例1: 基本生成 DSL（使用全类名） ---");

        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("basic_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成基本设备数据");
        definition.setAuthor("system");
        definition.setTags(new String[]{"device", "basic"});
        definition.setPriority(1);

        // 2. 设置上下文（使用全类名）
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 3);
        context.setScopeName("Device");
        context.setDebug(true);
        definition.setContext(context);

        // 3. 设置字段 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("groupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        definition.setFieldDsl(fieldDsl);

        // 4. 验证 DSL
        definition.validate();

        // 5. 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("生成的设备数量: " + devices.size());
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getGroupId() + ")")
        );
        System.out.println();
    }

    /**
     * 示例2: 复杂生成 DSL（包含嵌套和表达式，使用全类名）
     */
    private static void example2_ComplexGenerateDsl() {
        System.out.println("--- 示例2: 复杂生成 DSL（使用全类名） ---");

        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("complex_device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成包含嵌套任务的复杂设备数据");
        definition.setAuthor("advanced_user");
        definition.setTags(new String[]{"device", "complex", "nested"});
        definition.setPriority(2);

        // 2. 设置上下文（使用全类名）
        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 2);
        context.setScopeName("Device");
        context.setDebug(true);
        context.setStrict(true);
        definition.setContext(context);

        // 3. 设置字段 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("complex-device-", "&.index")));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("groupId", Map.of("$CHOICE", Arrays.asList("us", "gb", "cn")));
        fieldDsl.put("agentVersion", Map.of("$JOIN", Arrays.asList("2.0.", "&.index")));

        // 嵌套任务（使用全类名）
        Map<String, Object> tasksField = new HashMap<>();
        tasksField.put("TYPE", "LIST");
        tasksField.put("COUNT", 2);
        tasksField.put("MODEL", "com.xa.mass.base.model.Task");
        Map<String, Object> taskFields = new HashMap<>();
        taskFields.put("tid", Map.of("$UUID", true));
        taskFields.put("taskName", Map.of("$JOIN", Arrays.asList("ComplexTask-", "&.index", "-of-Device-", "&Device.index")));
        taskFields.put("taskCountry", "&Device.groupId");
        taskFields.put("taskInitNumber", Map.of("$RANGE", Arrays.asList(50, 200)));
        taskFields.put("batchSize", Map.of("$RANGE", Arrays.asList(2, 8)));
        tasksField.put("FIELDS", taskFields);
        fieldDsl.put("tasks", tasksField);

        // 表达式字段
        Map<String, Object> onlineStrategy = new HashMap<>();
        onlineStrategy.put("$EXPR", Map.of(
                "lang", "ql",
                "expr", "status == 'OFFLINE' ? 0 : range(10, 100)"
        ));
        fieldDsl.put("onlineStrategy", onlineStrategy);

        definition.setFieldDsl(fieldDsl);

        // 4. 设置组合规则
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_task_balance", "tasks.size() <= 3 ? 'balanced' : 'overloaded'");
        combineDsl.put("status_performance", "status == 'ONLINE' && agentVersion.startsWith('2.0') ? 'high_performance' : 'standard'");
        combineDsl.put("group_capacity", "groupId == 'us' ? 100 : groupId == 'gb' ? 50 : 30");
        definition.setCombineDsl(combineDsl);

        // 5. 设置扩展信息
        Map<String, Object> extensions = new HashMap<>();
        Map<String, Object> businessRules = new HashMap<>();
        businessRules.put("max_tasks_per_device", 5);
        businessRules.put("preferred_groups", Arrays.asList("us", "gb"));
        extensions.put("business_rules", businessRules);
        definition.setExtensions(extensions);

        // 6. 验证并生成
        definition.validate();
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("生成的复杂设备数量: " + devices.size());
        devices.forEach(device -> {
            System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getGroupId() + ")");
            System.out.println("    在线策略: " + device.getOnlineStrategy());
        });
        System.out.println();
    }

    /**
     * 示例3: 过滤器 DSL
     */
    private static void example3_FilterDsl() {
        System.out.println("--- 示例3: 过滤器 DSL ---");

        // 1. 创建过滤器 DSL 定义
        JsonDslDefinition filterDef = new JsonDslDefinition("online_device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("过滤在线设备");
        filterDef.setAuthor("system");
        filterDef.setPriority(10);

        // 2. 设置字段过滤条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        fieldDsl.put("groupId", Map.of("$in", Arrays.asList("us", "gb")));
        filterDef.setFieldDsl(fieldDsl);

        // 3. 设置组合过滤条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("battery_check", "batteryLevel >= 20");
        combineDsl.put("signal_check", "signalStrength >= 50");
        filterDef.setCombineDsl(combineDsl);

        // 4. 验证过滤器
        filterDef.validate();

        // 5. 转换为传统格式
        String filterConfig = JsonDslParser.toJson(filterDef);
        System.out.println("过滤器配置: " + filterConfig);

        // 6. 应用过滤器（需要先有数据）
        // List<Object> filtered = JsonDslEngine.filter(devices, filterConfig);
        System.out.println("过滤器 DSL 创建成功");
        System.out.println();
    }

    /**
     * 示例4: 转换 DSL
     */
    private static void example4_TransformDsl() {
        System.out.println("--- 示例4: 转换 DSL ---");

        // 1. 创建转换 DSL 定义
        JsonDslDefinition transformDef = new JsonDslDefinition("device_transformer", JsonDslDefinition.DslType.TRANSFORM);
        transformDef.setDescription("转换设备数据格式");
        transformDef.setAuthor("system");
        transformDef.setPriority(5);

        // 2. 设置转换规则
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$UPPER", "&.deviceId"));
        fieldDsl.put("status", Map.of("$MAP", Map.of("ONLINE", "active", "OFFLINE", "inactive")));
        fieldDsl.put("groupId", Map.of("$UPPER", "&.groupId"));
        transformDef.setFieldDsl(fieldDsl);

        // 3. 设置组合转换规则
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("full_name", "deviceId + '_' + groupId");
        combineDsl.put("status_code", "status == 'active' ? 1 : 0");
        transformDef.setCombineDsl(combineDsl);

        // 4. 验证转换器
        transformDef.validate();
        System.out.println("转换 DSL 创建成功");
        System.out.println();
    }

    /**
     * 示例5: 验证 DSL
     */
    private static void example5_ValidateDsl() {
        System.out.println("--- 示例5: 验证 DSL ---");

        // 1. 创建验证 DSL 定义
        JsonDslDefinition validateDef = new JsonDslDefinition("device_validator", JsonDslDefinition.DslType.VALIDATE);
        validateDef.setDescription("验证设备数据有效性");
        validateDef.setAuthor("system");
        validateDef.setPriority(1);

        // 2. 设置验证规则
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("required", true, "pattern", "^device-\\d+$"));
        fieldDsl.put("status", Map.of("enum", Arrays.asList("ONLINE", "OFFLINE")));
        fieldDsl.put("groupId", Map.of("required", true, "minLength", 2, "maxLength", 10));
        validateDef.setFieldDsl(fieldDsl);

        // 3. 设置组合验证规则
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("status_consistency", "status == 'ONLINE' ? batteryLevel > 0 : true");
        combineDsl.put("group_validity", "groupId in ['us', 'gb', 'cn', 'eu']");
        validateDef.setCombineDsl(combineDsl);

        // 4. 验证验证器
        validateDef.validate();
        System.out.println("验证 DSL 创建成功");
        System.out.println();
    }

    /**
     * 示例6: 从 JSON 解析 DSL（使用全类名）
     */
    private static void example6_ParseFromJson() {
        System.out.println("--- 示例6: 从 JSON 解析 DSL（使用全类名） ---");

        // 1. 标准化 DSL JSON
        String jsonDsl = """
                {
                  "unique_id": "json_device_generator",
                  "type": "generate",
                  "priority": 1,
                  "desc": "从 JSON 解析的设备生成器",
                  "version": "1.0",
                  "author": "json_user",
                  "tags": ["json", "device"],
                  "context": {
                    "MODEL": "com.xa.mass.base.model.Device",
                    "COUNT": 2,
                    "scope_name": "Device",
                    "debug": true
                  },
                  "fieldDsl": {
                    "deviceId": {"$JOIN": ["json-device-", "&.index"]},
                    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
                    "groupId": {"$CHOICE": ["us", "gb"]},
                    "createdTime": {
                      "$EXPR": {
                        "lang": "ql",
                        "expr": "now('yyyy-MM-dd HH:mm:ss')"
                      }
                    }
                  },
                  "combine_dsl": {
                    "status_group": "status == 'ONLINE' ? groupId : 'unknown'"
                  },
                  "extensions": {
                    "source": "json_parser"
                  }
                }
                """;

        // 2. 解析 JSON
        JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

        // 3. 验证解析结果
        System.out.println("解析的 DSL ID: " + definition.getUniqueId());
        System.out.println("DSL 类型: " + definition.getType());
        System.out.println("描述: " + definition.getDescription());
        System.out.println("作者: " + definition.getAuthor());
        System.out.println("标签: " + Arrays.toString(definition.getTags()));

        // 4. 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toJson(definition);
        List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);

        System.out.println("从 JSON 生成的设备数量: " + devices.size());
        devices.forEach(device ->
                System.out.println("  - " + device.getDeviceId() + " (" + device.getStatus() + ", " + device.getGroupId() + ")")
        );
        System.out.println();
    }
} 