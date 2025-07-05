package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.builtin.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.model.TaskCreateRequestDto;

import java.util.List;

/**
 * 基于 JSON-DSL 的 mock 设备/Token 生成器。
 */
public class MonkeyGenerator {

    static {

        // 注册类型
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Token", Token.class);
        TypeRegistry.register("RuleDefinition", com.xa.mass.engine.rules.RuleDefinition.class);
        TypeRegistry.register("TaskCreateRequestDto", TaskCreateRequestDto.class);

    }


    /**
     * 根据 JSON-DSL 生成设备列表（支持递归嵌套 Token）。
     * @param jsonDsl JSON-DSL 字符串
     * @return 设备列表
     */
    public static List<Device> generateDevices(String jsonDsl) {
        // 生成
        return JsonDslEngine.generateList(jsonDsl, Device.class);
    }

    /**
     * 根据 JSON-DSL 生成 Token 列表（假设 DSL 里有嵌套 Token 字段）。
     * @param jsonDsl JSON-DSL 字符串
     * @return Token 列表
     */
    public static List<Token> generateTokens(String jsonDsl) {
        // 目前仅支持通过 DSL 直接生成 Token 列表
        return JsonDslEngine.generateList(jsonDsl, Token.class);
    }

    /**
     * 根据 JSON-DSL 生成 TaskCreateRequestDto 列表。
     * @param jsonDsl JSON-DSL 字符串
     * @return 任务请求列表
     */
    public static List<TaskCreateRequestDto> generateTasks(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, TaskCreateRequestDto.class);
    }

    // 示例 JSON-DSL（推荐用 README.md 里的 DSL 语法）
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
                    "textContent": {"$JOIN": ["content for ", "&.index"]},
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


    // 示例 JSON-DSL（推荐用 README.md 里的 DSL 语法）
    public static String exampleJsonDsl() {
        return """
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
    }
} 