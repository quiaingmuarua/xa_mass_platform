package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.MessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import java.util.List;
import java.util.Map;

public class RepositoryManagerExample {


    public static void main(String[] args) {
        // 创建内存映射实例
        MessageMap<String, DeviceEntity> deviceMap = new InMemoryMessageMap<>("deviceMap");
        MessageMap<String, TokenEntity> tokenMap = new InMemoryMessageMap<>("tokenMap");
        MessageMap<String, TaskEntity> taskEntityMessageMap=new InMemoryMessageMap<>("taskEntityMessageMap");

        // 创建设备仓库管理器
        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(deviceMap, tokenMap);
        // 添加项目设备令牌映射
        deviceRepositoryManager.addProjectDeviceTokenMap(Project.DEMO_APP.getCode(), new InMemoryMessageMap<>("demoAppTokenMap"));

        TaskRepositoryManager taskRepositoryManager=new TaskRepositoryManager(taskEntityMessageMap);



        List<DeviceEntity> deviceEntityList=generateDevices();
        List<TokenEntity> tokenEntityList=generateTokens();
        // 为每个设备添加对应的令牌
        for (int i = 0; i < Math.min(deviceEntityList.size(), tokenEntityList.size()); i++) {
            TokenEntity token = tokenEntityList.get(i);
            token.setDeviceId(deviceEntityList.get(i).getDeviceId());
            deviceRepositoryManager.addDeviceBindToken(token);
        }
        deviceEntityList.forEach(deviceRepositoryManager::addDevice);

        //生成task
        List<TaskEntity> taskEntityList=generateTasks();
        taskEntityList.forEach(taskRepositoryManager::createTask);


        


        System.out.println("DeviceRepositoryManager initialized successfully");
        System.out.println("Generated " + deviceEntityList.size() + " devices");
        System.out.println("Generated " + taskEntityList.size() + " tasks");
        System.out.println("Generated " + tokenEntityList.size() + " tokens");
    }




    private  static  List<TaskEntity> generateTasks(){
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("task_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 50 个测试任务");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"task", "integration"});
        definition.setPriority(1);
        JsonDslContext context = new JsonDslContext("com.xa.mass.engine.v2.entity.TaskEntity", 50);
        context.setScopeName("TaskEntity");
        context.setDebug(false);
        definition.setContext(context);

        String fieldDslJson = """
                {
                  "taskId": {"$UUID": true},
                  "taskName": {"$JOIN": ["Task-", "&.index"]},
                  "project": {"$CHOICE": ["demoApp", "testApp", "otherApp"]},
                  "taskStatus": {"$CHOICE": ["NEW", "READY", "RUNNING", "PAUSED"]},
                  "taskCountry": {"$CHOICE": ["us", "gb", "cn"]},
                  "taskCount": {"$RANGE": [10, 200]},
                  "textContent": {"$JOIN": ["Content for task ", "&.index"]},
                  "createTime": {"$EXPR": "System.currentTimeMillis()"},
                  "updateTime": {"$EXPR": "System.currentTimeMillis()"}
                }
                """;
        
        // 解析JSON字符串为Map
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);
        
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), TaskEntity.class);
    }
    
     private static  List<TokenEntity> generateTokens() {
        // 1. 创建 DSL 定义
        JsonDslDefinition definition = new JsonDslDefinition("token_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("生成 100 个测试令牌");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"token", "integration"});
        definition.setPriority(1);
        JsonDslContext context = new JsonDslContext("com.xa.mass.engine.v2.entity.TokenEntity", 100);
        context.setScopeName("TokenEntity");
        context.setDebug(false);
        definition.setContext(context);

        String fieldDslJson = """
                {
                  "tokenId": {"$UUID": true},
                  "deviceId": {"$JOIN": ["device-", "&.index"]},
                  "project": {"$CHOICE": ["demoApp", "testApp", "otherApp"]},
                  "country": {"$CHOICE": ["us", "gb", "cn"]},
                  "platform": {"$CHOICE": ["android", "ios", "web"]},
                  "tokenStatus": {"$CHOICE": ["ACTIVE", "INACTIVE", "EXPIRED", "BLOCKED"]},
                  "lastUserTime": {"$EXPR": "System.currentTimeMillis()"},
                  "expireTime": {"$EXPR": "System.currentTimeMillis() + 86400000"},
                  "createTime": {"$EXPR": "System.currentTimeMillis()"},
                  "updateTime": {"$EXPR": "System.currentTimeMillis()"}
                }
                """;
        
        // 解析JSON字符串为Map
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);
        
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), TokenEntity.class);
    }


    /**
     * 使用新标准 DSL 生成设备
     */
    private static List<DeviceEntity> generateDevices() {
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

        String fieldDslJson = """
                {
                  "deviceId": {"$JOIN": ["", "&.index"]},
                  "groupId": {"$RANGE": [16, 65]},
                  "status": {"$CHOICE": ["OFFLINE", "ONLINE"]}
                }
                """;
        
        // 解析JSON字符串为Map
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, Object> fieldDsl = gson.fromJson(fieldDslJson, Map.class);
        definition.setFieldDsl(fieldDsl);
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), DeviceEntity.class);


    }
}
