package com.xa.mass.engine.monkey;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.TypeRegistry;
import com.xa.mass.engine.model.TaskCreateRequestDto;

import java.util.List;
import java.util.stream.Collectors;

public class MonkeyTaskGenerator {
    /**
     * 根据 JSON-DSL 生成 TaskCreateRequestDto 列表。
     * @param jsonDsl JSON-DSL 字符串
     * @return 任务请求列表
     */
    public static List<TaskCreateRequestDto> generateTasks(String jsonDsl) {
        TypeRegistry.register("TaskCreateRequestDto", TaskCreateRequestDto.class);
        List<Object> result = JsonDslEngine.generate(jsonDsl);
        return result.stream()
                .filter(TaskCreateRequestDto.class::isInstance)
                .map(TaskCreateRequestDto.class::cast)
                .collect(Collectors.toList());
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
} 