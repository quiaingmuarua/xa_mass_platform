package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.redis.LettuceRedisMessageMap;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.monkey.MockEngineGenerator;

import java.util.List;
import java.util.Map;

public class RepositoryManagerExample {


    public static void main(String[] args) {
        String queueType = "内存";
        MessageMap<String, DeviceEntity> deviceMap;
        MessageMap<String, TokenEntity> tokenMap;
        MessageMap<String, TaskEntity> taskEntityMessageMap;
        MessageMap<String, TokenEntity> demoAppTokenMap;
        if ("内存".equals(queueType)) {
            System.out.println("=== 内存队列示例 ===");
            // 初始化内存队列
            deviceMap = new InMemoryMessageMap<>("deviceMap");
            tokenMap = new InMemoryMessageMap<>("tokenMap");
            taskEntityMessageMap = new InMemoryMessageMap<>("taskEntityMessageMap");
            demoAppTokenMap = new InMemoryMessageMap<>("demoAppTokenMap");

        } else {
            System.out.println("=== Redis队列示例 ===");
            // 1. 初始化全局Redis连接
            RedisConnectionManager.init("localhost", 6379, null, 0);
            // 2. 创建Redis消息映射
            deviceMap = new LettuceRedisMessageMap<>("xa_mass_platform::deviceMap", String.class, DeviceEntity.class);
            tokenMap = new LettuceRedisMessageMap<>("xa_mass_platform::tokenMap", String.class, TokenEntity.class);
            taskEntityMessageMap = new LettuceRedisMessageMap<>("xa_mass_platform::taskEntityMessageMap", String.class, TaskEntity.class);
            demoAppTokenMap = new LettuceRedisMessageMap<>("xa_mass_platform::demoAppTokenMap", String.class, TokenEntity.class);


        }
        //init manager
        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(deviceMap, tokenMap);
        TaskRepositoryManager taskRepositoryManager = new TaskRepositoryManager(taskEntityMessageMap,
                queueType.equals("内存") ? QueueProviderType.IN_MEMORY : QueueProviderType.REDIS);

        //mock 关键数据
        mockGenerate(taskRepositoryManager, deviceRepositoryManager, queueType);
        //匹配任务和device



    }


    private static void mockGenerate(TaskRepositoryManager taskRepositoryManager, DeviceRepositoryManager deviceRepositoryManager, String queueType) {
        //init entity list
        String deviceFieldDslJson = """
                {
                  "deviceId": {"$JOIN": ["", "&.index"]},
                  "groupId": {"$RANGE": [16, 65]},
                  "status": {"$CHOICE": ["OFFLINE", "ONLINE"]}
                }
                """;
        String tokenFieldDslJson = """
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
        String taskFieldDslJson = """
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
        List<DeviceEntity> deviceEntityList = MockEngineGenerator.generateDevices(deviceFieldDslJson, 300);
        List<TokenEntity> tokenEntityList = MockEngineGenerator.generateTokens(tokenFieldDslJson, 100);
        List<TaskEntity> taskEntityList = MockEngineGenerator.generateTasks(taskFieldDslJson, 50);
        //bind to manager
        initEnv(deviceEntityList, tokenEntityList, taskEntityList, deviceRepositoryManager, taskRepositoryManager, queueType);
        //push seed to queue
        pushSeed(taskEntityList, taskRepositoryManager);
    }


    /**
     * 公共示例执行逻辑
     */
    private static void initEnv(List<DeviceEntity> deviceEntityList, List<TokenEntity> tokenEntityList, List<TaskEntity> taskEntityList, DeviceRepositoryManager deviceRepositoryManager,TaskRepositoryManager taskRepositoryManager,String queueType) {
        for (int i = 0; i < Math.min(deviceEntityList.size(), tokenEntityList.size()); i++) {
            TokenEntity token = tokenEntityList.get(i);
            token.setDeviceId(deviceEntityList.get(i).getDeviceId());
            deviceRepositoryManager.addDeviceBindToken(token);
        }
        deviceEntityList.forEach(deviceRepositoryManager::addDevice);
        taskEntityList.forEach(taskRepositoryManager::createTask);
        System.out.println("[" + queueType + "] DeviceRepositoryManager initialized successfully");
        System.out.println("[" + queueType + "] Generated " + deviceEntityList.size() + " devices");
        System.out.println("[" + queueType + "] Generated " + taskEntityList.size() + " tasks");
        System.out.println("[" + queueType + "] Generated " + tokenEntityList.size() + " tokens");

    }


    private static void pushSeed(List<TaskEntity> taskEntityList, TaskRepositoryManager taskRepositoryManager){
        taskEntityList.forEach(taskEntity -> {
            for (int i = 0; i < taskEntity.getTaskCount(); i++) {
                taskRepositoryManager.addTaskSeed(taskEntity.getTaskId(), "seed-" + i+taskEntity.getTaskId());
            }
        });

    }




}
