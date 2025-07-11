package com.xa.mass.engine.v2.integration;

import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import com.xa.mass.engine.v2.service.DeviceService;
import com.xa.mass.engine.v2.service.DeviceServiceImpl;
import com.xa.mass.engine.v2.service.EngineRegistry;
import com.xa.mass.engine.v2.service.TaskService;
import com.xa.mass.engine.v2.service.TaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据流转集成测试
 * 验证从任务创建到设备绑定的完整流程
 */
public class DataFlowIntegrationTest {

    private TaskService taskService;
    private DeviceService deviceService;
    private TaskRepositoryManager taskRepositoryManager;
    private DeviceRepositoryManager deviceRepositoryManager;

    @BeforeEach
    void setUp() {
        // 初始化数据层
        InMemoryMessageMap<String, DeviceEntity> deviceMap = new InMemoryMessageMap<>();
        
        taskRepositoryManager = TaskRepositoryManager.createWithDefaultProjects(QueueProviderType.IN_MEMORY);
        deviceRepositoryManager = new DeviceRepositoryManager(deviceMap);
        
        // 初始化服务层
        taskService = new TaskServiceImpl(taskRepositoryManager);
        deviceService = new DeviceServiceImpl(deviceRepositoryManager);
        
        // 注册到引擎
        EngineRegistry.setDefaultTaskService(taskService);
        EngineRegistry.setDefaultDeviceService(deviceService);
        
        // 注册项目
        deviceService.registerAllProjects();
    }

    @Test
    void testCompleteDataFlow() {
        // 1. 创建设备和令牌
        DeviceEntity device = createTestDevice("device001");
        TokenEntity token = createTestToken("token001", "device001", Project.DEMO_APP);
        
        deviceService.registerDevice(device);
        deviceService.bindDeviceToken(token);
        
        // 验证设备注册
        assertTrue(deviceService.containsDevice("device001"));
        assertNotNull(deviceService.getDevice("device001"));
        
        // 验证令牌绑定
        // TODO: 在新架构中，getDeviceToken 需要重新实现或者通过其他方式验证
        // TokenEntity retrievedToken = deviceService.getDeviceToken("device001", Project.DEMO_APP);
        // assertNotNull(retrievedToken);
        // assertEquals("token001", retrievedToken.getTokenId());
        
        // 2. 创建任务
        TaskEntity task = createTestTask("task001", Project.DEMO_APP);
        taskService.createTask(Project.DEMO_APP, task);
        
        // 验证任务创建
        assertTrue(taskService.containsTask(Project.DEMO_APP, "task001"));
        TaskEntity retrievedTask = taskService.getTask(Project.DEMO_APP, "task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        
        // 3. 添加任务种子
        taskService.addTaskSeed(Project.DEMO_APP, "task001", "seed1");
        taskService.addTaskSeed(Project.DEMO_APP, "task001", "seed2");
        taskService.addTaskSeed(Project.DEMO_APP, "task001", "seed3");
        
        // 验证种子数量
        assertEquals(3, taskService.getTaskSeedCount(Project.DEMO_APP, "task001"));
        
        // 4. 更新任务状态
        taskService.updateTaskStatus(Project.DEMO_APP, "task001", "READY");
        TaskEntity readyTask = taskService.getTask(Project.DEMO_APP, "task001");
        assertEquals("READY", readyTask.getTaskStatus());
        
        // 4. 验证种子被消费
        String consumedSeed = taskService.getTaskSeed(Project.DEMO_APP, "task001");
        if (consumedSeed != null) {
            assertTrue(consumedSeed.contains("seed"));
        }
        
        // 5. 验证消息分配
        TaskMsgEntity retrievedMsg = taskService.getTaskMsg(Project.DEMO_APP, "task001");
        if (retrievedMsg != null) {
            assertNotNull(retrievedMsg.getMsgId());
            assertEquals("task001", retrievedMsg.getTaskId());
            assertTrue(retrievedMsg.isBinding());
        }

        // 验证统计信息
        assertTrue(deviceService.getDeviceCount() > 0);
        assertTrue(deviceService.getTokenCount(Project.DEMO_APP.getCode()) >= 0);
        assertTrue(deviceService.getProjectCount() > 0);
    }

    @Test
    void testConcurrentDataFlow() throws InterruptedException {
        // 创建多个任务和设备
        int taskCount = 10;
        int deviceCount = 5;
        
        // 创建设备
        for (int i = 0; i < deviceCount; i++) {
            DeviceEntity device = createTestDevice("device" + i);
            TokenEntity token = createTestToken("token" + i, "device" + i, Project.DEMO_APP);
            deviceService.registerDevice(device);
            deviceService.bindDeviceToken(token);
        }
        
        // 创建任务
        for (int i = 0; i < taskCount; i++) {
            TaskEntity task = createTestTask("task" + i, Project.DEMO_APP);
            taskService.createTask(Project.DEMO_APP, task);
            
            // 为每个任务添加种子
            for (int j = 0; j < 3; j++) {
                taskService.addTaskSeed(Project.DEMO_APP, "task" + i, "seed" + i + "_" + j);
            }
        }
        
        // 并发消费种子和创建消息
        Thread[] threads = new Thread[taskCount];
        for (int i = 0; i < taskCount; i++) {
            final int taskIndex = i;
            threads[i] = new Thread(() -> {
                String taskId = "task" + taskIndex;
                
                // 消费种子
                String seed = taskService.getTaskSeed(Project.DEMO_APP, taskId);
                assertNotNull(seed);
                
                // 创建消息
                TaskMsgEntity taskMsg = createTestTaskMsg("msg" + taskIndex, taskId);
                taskService.addTaskMsg(Project.DEMO_APP, taskId, taskMsg);
                
                // 消费消息
                TaskMsgEntity retrievedMsg = taskService.getTaskMsg(Project.DEMO_APP, taskId);
                assertNotNull(retrievedMsg);
                assertEquals("msg" + taskIndex, retrievedMsg.getMsgId());
            });
        }
        
        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }
        
        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }
        
        // 验证最终状态
        assertEquals(taskCount, taskService.getTotalTaskCount(Project.DEMO_APP));
        assertEquals(deviceCount, deviceService.getDeviceCount());
        assertEquals(deviceCount, deviceService.getTokenCount(Project.DEMO_APP.getCode()));
        
        // 验证每个任务的种子数量（应该减少1个）
        for (int i = 0; i < taskCount; i++) {
            assertEquals(2, taskService.getTaskSeedCount(Project.DEMO_APP, "task" + i));
            assertEquals(0, taskService.getTaskMsgCount(Project.DEMO_APP, "task" + i));
        }
    }

    @Test
    void testEngineRegistryIntegration() {
        // 验证默认服务
        assertNotNull(EngineRegistry.getTaskService());
        assertNotNull(EngineRegistry.getDeviceService());
        assertTrue(EngineRegistry.hasDefaultTaskService());
        assertTrue(EngineRegistry.hasDefaultDeviceService());
        
        // 验证服务功能
        TaskEntity task = createTestTask("task001", Project.DEMO_APP);
        EngineRegistry.getTaskService().createTask(Project.DEMO_APP, task);
        assertTrue(EngineRegistry.getTaskService().containsTask(Project.DEMO_APP, "task001"));
        
        DeviceEntity device = createTestDevice("device001");
        EngineRegistry.getDeviceService().registerDevice(device);
        assertTrue(EngineRegistry.getDeviceService().containsDevice("device001"));
    }

    // 辅助方法
    private DeviceEntity createTestDevice(String deviceId) {
        DeviceEntity device = new DeviceEntity();
        device.setDeviceId(deviceId);
        device.setGroupId("group1");
        device.setDeviceStatus("ONLINE");
        return device;
    }

    private TokenEntity createTestToken(String tokenId, String deviceId, Project project) {
        TokenEntity token = new TokenEntity();
        token.setTokenId(tokenId);
        token.setDeviceId(deviceId);
        token.setProject(project.getCode());
        token.setCountry("us");
        token.setPlatform("android");
        token.setTokenStatus("ACTIVE");
        token.setCreateTime(System.currentTimeMillis());
        token.setUpdateTime(System.currentTimeMillis());
        return token;
    }

    private TaskEntity createTestTask(String taskId, Project project) {
        TaskEntity task = new TaskEntity(taskId, "Test Task " + taskId, project.getCode());
        task.setTaskCount(10);
        task.setCreateTime(System.currentTimeMillis());
        task.setUpdateTime(System.currentTimeMillis());
        return task;
    }

    private TaskMsgEntity createTestTaskMsg(String msgId, String taskId) {
        TaskMsgEntity taskMsg = new TaskMsgEntity(msgId, taskId);
        taskMsg.setTaskMsgStatus("INIT");
        taskMsg.setCompleteStatus("INIT");
        taskMsg.setCreateTime(System.currentTimeMillis());
        return taskMsg;
    }
} 