package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.redis.LettuceRedisMessageMap;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.monkey.MockEngineGenerator;
import com.xa.mass.engine.v2.service.TaskService;
import com.xa.mass.engine.v2.service.TaskServiceImpl;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

import java.util.List;

public class RepositoryManagerExample {


    public static void main(String[] args) {

        System.out.println("=== Redis队列示例 ===");
        // 1. 初始化全局Redis连接
        RedisConnectionManager.init("localhost", 6379, null, 0);
        // 2. 创建Redis消息映射
        MessageMap<String, DeviceEntity> deviceMap = new LettuceRedisMessageMap<>(QueueKeyUtil.getDeviceHashKey(), DeviceEntity.class);
        //init manager
        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(deviceMap);
        TaskRepositoryManager taskRepositoryManager = TaskRepositoryManager.createWithDefaultProjects(
               QueueProviderType.REDIS);
        TaskService taskService = new TaskServiceImpl(taskRepositoryManager);

        //mock 关键数据
        mockGenerate(taskService, deviceRepositoryManager, "REDIS");
        //匹配任务和device



    }


    private static void mockGenerate(TaskService taskService, DeviceRepositoryManager deviceRepositoryManager, String queueType) {
        // 注册所有项目分组
        deviceRepositoryManager.registerAllProjects(project -> new LettuceRedisMessageMap<>(QueueKeyUtil.getProjectAllTokenHashKey(project),TokenEntity.class));

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
                  "project": {"$CHOICE": ["demoApp", "testApp"]},
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
                  "project": {"$CHOICE": ["demoApp", "testApp"]},
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
        initEnv(deviceEntityList, tokenEntityList, taskEntityList, deviceRepositoryManager, taskService, queueType);
        //push seed to queue
        pushSeed(taskEntityList, taskService);
    }


    /**
     * 公共示例执行逻辑
     */
    private static void initEnv(List<DeviceEntity> deviceEntityList, List<TokenEntity> tokenEntityList, List<TaskEntity> taskEntityList, DeviceRepositoryManager deviceRepositoryManager, TaskService taskService, String queueType) {
        for (int i = 0; i < Math.min(deviceEntityList.size(), tokenEntityList.size()); i++) {
            TokenEntity token = tokenEntityList.get(i);
            token.setDeviceId(deviceEntityList.get(i).getDeviceId());
            deviceRepositoryManager.saveToken(token.getProject(), token);
        }
        deviceEntityList.forEach(deviceRepositoryManager::saveDevice);
        taskEntityList.forEach(task -> taskService.createTask(Project.fromCode(task.getProject()), task));
        System.out.println("[" + queueType + "] DeviceRepositoryManager initialized successfully");
        System.out.println("[" + queueType + "] Generated " + deviceEntityList.size() + " devices");
        System.out.println("[" + queueType + "] Generated " + taskEntityList.size() + " tasks");
        System.out.println("[" + queueType + "] Generated " + tokenEntityList.size() + " tokens");

    }


    private static void pushSeed(List<TaskEntity> taskEntityList, TaskService taskService){
        taskEntityList.forEach(taskEntity ->
        {
            for (int i = 0; i < taskEntity.getTaskCount(); i++) {
                String seedContent = "seed-" + i + taskEntity.getTaskId()+"-text";
                taskService.addTaskSeed(Project.fromCode(taskEntity.getProject()), taskEntity.getTaskId(), seedContent);
            }
        });

    }




}
