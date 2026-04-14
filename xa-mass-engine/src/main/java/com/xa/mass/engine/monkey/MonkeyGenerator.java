package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.generate.TypeRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.model.TaskCreateRequestDto;

import java.util.List;

/**
 * 鍩轰簬 JSON-DSL 鐨?mock 璁惧/Token 鐢熸垚鍣ㄣ€?
 */
public class MonkeyGenerator {

    static {

        // 娉ㄥ唽绫诲瀷
        TypeRegistry.register("Device", Device.class);
        TypeRegistry.register("Token", Token.class);
        TypeRegistry.register("RuleDefinition", com.xa.mass.engine.rules.RuleDefinition.class);
        TypeRegistry.register("TaskCreateRequestDto", TaskCreateRequestDto.class);

    }


    /**
     * 鏍规嵁 JSON-DSL 鐢熸垚璁惧鍒楄〃锛堟敮鎸侀€掑綊宓屽 Token锛夈€?
     * @param jsonDsl JSON-DSL 瀛楃涓?
     * @return 璁惧鍒楄〃
     */
    public static List<Device> generateDevices(String jsonDsl) {
        // 鐢熸垚
        return JsonDslEngine.generateList(jsonDsl, Device.class);
    }

    /**
     * 鏍规嵁 JSON-DSL 鐢熸垚 Token 鍒楄〃锛堝亣璁?DSL 閲屾湁宓屽 Token 瀛楁锛夈€?
     * @param jsonDsl JSON-DSL 瀛楃涓?
     * @return Token 鍒楄〃
     */
    public static List<Token> generateTokens(String jsonDsl) {
        // 鐩墠浠呮敮鎸侀€氳繃 DSL 鐩存帴鐢熸垚 Token 鍒楄〃
        return JsonDslEngine.generateList(jsonDsl, Token.class);
    }

    /**
     * 鏍规嵁 JSON-DSL 鐢熸垚 TaskCreateRequestDto 鍒楄〃銆?
     * @param jsonDsl JSON-DSL 瀛楃涓?
     * @return 浠诲姟璇锋眰鍒楄〃
     */
    public static List<TaskCreateRequestDto> generateTasks(String jsonDsl) {
        return JsonDslEngine.generateList(jsonDsl, TaskCreateRequestDto.class);
    }

    // 绀轰緥 JSON-DSL锛堟帹鑽愮敤 README.md 閲岀殑 DSL 璇硶锛?
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


    // 绀轰緥 JSON-DSL锛堟帹鑽愮敤 README.md 閲岀殑 DSL 璇硶锛?
    public static String exampleJsonDsl() {
        return """
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
    }
} 