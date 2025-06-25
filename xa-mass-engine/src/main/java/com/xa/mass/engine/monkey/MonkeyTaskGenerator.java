package com.xa.mass.engine.monkey;

import com.google.gson.*;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import java.util.*;

public class MonkeyTaskGenerator {
    public static class TaskBatchConfig {
        public String taskNameTemplate = "Task-{country}-{i}";
        public List<String> countryList = List.of("us");
        public int countPerCountry = 1;
        public int msgPerTask = 50;
        public int batchSize = 5;
        public String project = "demoApp";
    }

    /**
     * 解析 tasks JSON-DSL，批量生成 TaskCreateRequestDto
     */
    public static List<TaskCreateRequestDto> generateTasks(JsonArray tasksArr) {
        List<TaskCreateRequestDto> tasks = new ArrayList<>();
        Gson gson = new Gson();
        for (JsonElement elem : tasksArr) {
            TaskBatchConfig cfg = gson.fromJson(elem, TaskBatchConfig.class);
            for (String country : cfg.countryList) {
                for (int i = 0; i < cfg.countPerCountry; i++) {
                    TaskCreateRequestDto dto = new TaskCreateRequestDto();
                    String taskName = cfg.taskNameTemplate.replace("{country}", country).replace("{i}", String.valueOf(i));
                    dto.setTaskName(taskName);
                    dto.setProject(cfg.project);
                    dto.setCountryCode(country);
                    dto.setUserId("user-" + country);
                    dto.setTextContent("content for " + country);
                    List<String> targetList = new ArrayList<>();
                    for (int j = 0; j < cfg.msgPerTask; j++) {
                        targetList.add("number-" + country + "-" + j);
                    }
                    dto.setTargetList(targetList);
                    dto.setBatchSize(cfg.batchSize);
                    tasks.add(dto);
                }
            }
        }
        return tasks;
    }

    // 示例 tasks JSON-DSL
    public static String exampleTasksJsonDsl() {
        return "[\n" +
                "  {\n" +
                "    \"taskNameTemplate\": \"Task-{country}-{i}\",\n" +
                "    \"countryList\": [\"us\", \"gb\"],\n" +
                "    \"countPerCountry\": 2,\n" +
                "    \"msgPerTask\": 50,\n" +
                "    \"batchSize\": 5,\n" +
                "    \"project\": \"demoApp\"\n" +
                "  }\n" +
                "]";
    }
} 