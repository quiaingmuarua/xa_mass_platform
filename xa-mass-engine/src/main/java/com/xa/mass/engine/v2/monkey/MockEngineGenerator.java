package com.xa.mass.engine.v2.monkey;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;

import java.util.List;
import java.util.Map;

public class MockEngineGenerator {


    public static List<DeviceEntity> generateDevices(String fieldDslJson , int count) {
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 300 个测试设备");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"device", "integration"});
        definition.setPriority(1);
        JsonDslContext context = new JsonDslContext("com.xa.mass.engine.v2.entity.DeviceEntity", 300);
        context.setScopeName("DeviceEntity");
        context.setDebug(false);
        definition.setContext(context);

        // 解析JSON字符串为Map
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), DeviceEntity.class);


    }



    public static List<TaskEntity> generateTasks(  String fieldDslJson,int count){
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("task_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 50 个测试任务");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"task", "integration"});
        definition.setPriority(1);
        JsonDslContext context = new JsonDslContext("com.xa.mass.engine.v2.entity.TaskEntity", count);
        context.setScopeName("TaskEntity");
        context.setDebug(false);
        definition.setContext(context);

        // 解析JSON字符串为Map
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);

        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), TaskEntity.class);
    }

    public static List<TokenEntity> generateTokens(String fieldDslJson, int count) {
        JsonDslDefinition definition = new JsonDslDefinition("token_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 " + count + " 个测试令牌");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"token", "integration"});
        definition.setPriority(1);
        JsonDslContext context = new JsonDslContext("com.xa.mass.engine.v2.entity.TokenEntity", count);
        context.setScopeName("TokenEntity");
        context.setDebug(false);
        definition.setContext(context);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), TokenEntity.class);
    }
}
