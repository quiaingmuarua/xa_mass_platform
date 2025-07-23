package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.monkey.MockEngineGenerator;
import com.xa.mass.engine.v2.schedule.AutoRecoveryService;
import com.xa.mass.engine.v2.schedule.DaemonServiceRegistry;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class TaskBindDeviceExample {


    public static void main(String[] args) {


        DeviceRepositoryManager deviceRepositoryManager = new DeviceRepositoryManager(new InMemoryMessageMap<>("deviceMap",DeviceEntity.class));
        TaskRepositoryManager taskRepositoryManager = TaskRepositoryManager.createWithDefaultProjects(MessageProviderType.IN_MEMORY);

        generateData(deviceRepositoryManager, taskRepositoryManager);
        System.out.println("TaskRepositoryManager initialized successfully");
        //开始绑定task 和 token 生成taskMsg
        deviceRepositoryManager.getAllDevices().forEach(System.out::println);

        Collection<TaskEntity> taskEntityList= taskRepositoryManager.getProjectTasks(Project.DEMO_APP);

        deviceRepositoryManager.getProjectTokens(Project.DEMO_APP.getCode()).forEach(System.out::println);


        taskEntityList.forEach(System.out::println);


        taskEntityList.forEach(taskEntity -> {
            if(Objects.equals(taskEntity.getTaskStatus(), "NEW")){
                taskEntity.setTaskStatus("BLOCKED");
            }

        });
        DaemonServiceRegistry daemonServiceRegistry = new DaemonServiceRegistry();
        daemonServiceRegistry.register( new AutoRecoveryService(taskRepositoryManager,deviceRepositoryManager,Project.DEMO_APP));
        daemonServiceRegistry.startAll();




        //阻塞主进程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


    }

    public static void generateData(DeviceRepositoryManager deviceRepositoryManager, TaskRepositoryManager taskRepositoryManager) {
        // 注册所有项目分组
        deviceRepositoryManager.registerAllProjects(project -> new InMemoryMessageMap<>(project.getCode() + "TokenMap",TokenEntity.class));

        //mock device
        List<DeviceEntity> deviceEntityList = mockDevices( 100);
        deviceEntityList.forEach(deviceRepositoryManager::saveDevice);

        //mock device token binding
        List<TokenEntity> generatedTokens =mockTokens( 100);
        generatedTokens.forEach(tokenEntity -> 
            deviceRepositoryManager.saveToken(tokenEntity.getProject(), tokenEntity));

        //获取demo app project token
        for (Project project : Project.values()) {
            List<TokenEntity> tokenEntityList = deviceRepositoryManager.getProjectTokens(project.getCode());

            for (TokenEntity token : tokenEntityList) {
                token.setDeviceId(token.getDeviceId());
                deviceRepositoryManager.saveToken(token.getProject(), token);
                //绑定到设备
                DeviceEntity device =deviceRepositoryManager.getDevice(token.getDeviceId());
                if(device!=null){
                    device.setTokenForProject(token.getProject(), token.getTokenId());
                }
            }
            ;
        };
        List<TaskEntity> taskEntityList = mockTasks(10);
        System.out.println("DeviceRepositoryManager initialized successfully");
        System.out.println("Generated " + deviceEntityList.size() + " devices");
        System.out.println("Generated " + generatedTokens.size() + " tokens");
        System.out.println("Generated " + taskEntityList.size() + " tasks");

        
        // 创建任务
        taskEntityList.forEach(taskEntity -> {
            taskRepositoryManager.saveTask(Project.fromCode(taskEntity.getProject()), taskEntity);
            taskRepositoryManager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.fromCode(taskEntity.getProject()), taskEntity.getTaskId()));
            taskRepositoryManager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.fromCode(taskEntity.getProject()), taskEntity.getTaskId()));
        });
        
        // 添加种子
        taskEntityList.forEach(taskEntity ->
        {
            for (int i = 0; i < taskEntity.getTaskCount(); i++) {
                String seed = "seed-" + i + taskEntity.getTaskId();
                taskRepositoryManager.addSeed(QueueKeyUtil.getSeedStreamKey(taskEntity), seed);
            }
        });

    }

    /**
     * 生成 mock 设备、令牌、任务
     */
    public static List<DeviceEntity> mockDevices(int count) {
        String deviceFieldDslJson = """
                {
                  "deviceId": "&.index",
                  "groupId": {"$RANGE": [16, 65]},
                  "agentVersion": {"$CHOICE": ["1.0", "1.1"]},
                  "deviceStatus": {"$CHOICE": ["OFFLINE", "ONLINE"]},
                  "createTime": {"$EXPR": "System.currentTimeMillis()"},
                  "updateTime": {"$EXPR": "System.currentTimeMillis()"}
                }
                """;
        return MockEngineGenerator.generateDevices(deviceFieldDslJson, count);
    }

    public static List<TokenEntity> mockTokens(int count) {
        String tokenFieldDslJson = """
                {
                  "tokenId": {"$UUID": true},
                  "deviceId": {"$RANGE": [0, 100]},
                  "project": {"$CHOICE": ["demoApp", "testApp"]},
                  "country": {"$CHOICE": ["us", "gb", "cn"]},
                  "platform": {"$CHOICE": ["android", "ios", "web"]},
                  "tokenStatus": {"$CHOICE": ["ACTIVE", "INACTIVE"]},
                  "lastUserTime": {"$EXPR": "System.currentTimeMillis()"},
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
                  "taskStatus": {"$CHOICE": ["NEW", "PAUSED"]},
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
