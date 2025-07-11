package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.monkey.MockEngineGenerator;

import java.util.List;

public class TaskBindDeviceExample {


    public static void main(String[] args) {
        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(new InMemoryMessageMap<>("deviceMap"), new InMemoryMessageMap<>("tokenMap"));
        TaskRepositoryManager taskRepositoryManager = new TaskRepositoryManager(new InMemoryMessageMap<>("taskEntityMessageMap"), QueueProviderType.IN_MEMORY);

        generateData(deviceRepositoryManager, taskRepositoryManager);
        System.out.println("TaskRepositoryManager initialized successfully");
        //开始绑定task 和 token 生成taskMsg

//          taskRepositoryManager.ge

    }

    public static void generateData(DeviceRepositoryManager deviceRepositoryManager, TaskRepositoryManager taskRepositoryManager) {

        // 初始化内存队列
        List<DeviceEntity> deviceEntityList = mockDevices(100);
        List<TokenEntity> tokenEntityList = mockTokens(100);
        List<TaskEntity> taskEntityList = mockTasks(1);
        deviceEntityList.forEach(deviceRepositoryManager::addDevice);

        for (int i = 0; i < Math.min(deviceEntityList.size(), tokenEntityList.size()); i++) {
            TokenEntity token = tokenEntityList.get(i);
            token.setDeviceId(deviceEntityList.get(i).getDeviceId());
            deviceRepositoryManager.addDeviceBindToken(token);
        }
        System.out.println("DeviceRepositoryManager initialized successfully");
        System.out.println("Generated " + deviceEntityList.size() + " devices");
        System.out.println("Generated " + tokenEntityList.size() + " tokens");
        System.out.println("Generated " + taskEntityList.size() + " tasks");
        taskEntityList.forEach(taskRepositoryManager::createTask);
        taskEntityList.forEach(taskEntity ->
        {
            for (int i = 0; i < taskEntity.getTaskCount(); i++) {
                String seed = "seed-" + i + taskEntity.getTaskId();
                taskRepositoryManager.addTaskSeed(taskEntity.getTaskId(), seed);
            }
        });

    }

    /**
     * 生成 mock 设备、令牌、任务
     */
    public static List<DeviceEntity> mockDevices(int count) {
        String deviceFieldDslJson = """
                {
                  "deviceId": {"$JOIN": ["", "&.index"]},
                  "groupId": {"$RANGE": [16, 65]},
                  "status": {"$CHOICE": ["OFFLINE", "ONLINE"]}
                }
                """;
        return MockEngineGenerator.generateDevices(deviceFieldDslJson, count);
    }

    public static List<TokenEntity> mockTokens(int count) {
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
        return MockEngineGenerator.generateTokens(tokenFieldDslJson, count);
    }

    public static List<TaskEntity> mockTasks(int count) {
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
        return MockEngineGenerator.generateTasks(taskFieldDslJson, count);
    }
}
