package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.MessageMap;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskRepositoryManager 测试类
 */
public class TaskRepositoryManagerTest {

    private TaskRepositoryManager manager;
    private MessageMap<String, TaskEntity> taskMap;

    @BeforeEach
    void setUp() {
        taskMap = new InMemoryMessageMap<>();
        manager = new TaskRepositoryManager(taskMap);
    }

    /**
     * 创建测试任务实体
     */
    private TaskEntity createTestTask(String taskId) {
        TaskEntity task = new TaskEntity(taskId, "Test Task " + taskId, "PROJECT_A");
        return task;
    }

    /**
     * 创建测试任务消息实体
     */
    private TaskMsgEntity createTestTaskMsg(String msgId, String taskId) {
        TaskMsgEntity taskMsg = new TaskMsgEntity(msgId, taskId);
        return taskMsg;
    }

    @Test
    void testConstructor() {
        // 测试正常构造
        assertNotNull(manager);
        
        // 测试空参数构造 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> new TaskRepositoryManager(null));
    }

    @Test
    void testCreateTask() {
        // 测试正常创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 验证任务已创建
        TaskEntity retrievedTask = manager.getTask("task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        assertEquals("Test Task task001", retrievedTask.getTaskName());
        assertEquals("PROJECT_A", retrievedTask.getProject());
        assertEquals("NEW", retrievedTask.getTaskStatus());
        
        // 验证任务存在检查
        assertTrue(manager.containsTask("task001"));
        
        // 验证种子队列和消息队列已初始化
        assertEquals(0, manager.getTaskSeedCount("task001"));
        assertEquals(0, manager.getTaskMsgCount("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.createTask(null));
        
        // 测试任务ID为null - 应该抛出异常
        TaskEntity invalidTask = new TaskEntity();
        assertThrows(NullPointerException.class, () -> manager.createTask(invalidTask));
    }

    @Test
    void testAddTaskSeed() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试正常添加种子
        manager.addTaskSeed("task001", "seed1");
        manager.addTaskSeed("task001", "seed2");
        
        // 验证种子数量
        assertEquals(2, manager.getTaskSeedCount("task001"));
        
        // 测试获取种子
        String seed1 = manager.getTaskSeed("task001");
        assertEquals("seed1", seed1);
        
        String seed2 = manager.getTaskSeed("task001");
        assertEquals("seed2", seed2);
        
        // 验证种子已消费
        assertEquals(0, manager.getTaskSeedCount("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.addTaskSeed(null, "seed"));
        assertThrows(NullPointerException.class, () -> manager.addTaskSeed("task001", null));
        
        // 测试不存在的任务 - 应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> manager.addTaskSeed("nonExistent", "seed"));
    }

    @Test
    void testGetTaskSeed() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试获取不存在的种子
        String nonExistentSeed = manager.getTaskSeed("task001");
        assertNull(nonExistentSeed);
        
        // 添加种子后获取
        manager.addTaskSeed("task001", "seed1");
        String seed = manager.getTaskSeed("task001");
        assertEquals("seed1", seed);
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.getTaskSeed(null));
    }

    @Test
    void testUpdateTaskStatus() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试正常更新状态
        manager.updateTaskStatus("task001", "READY");
        
        // 验证状态已更新
        TaskEntity updatedTask = manager.getTask("task001");
        assertEquals("READY", updatedTask.getTaskStatus());
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.updateTaskStatus(null, "READY"));
        assertThrows(NullPointerException.class, () -> manager.updateTaskStatus("task001", null));
        
        // 测试不存在的任务 - 应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> manager.updateTaskStatus("nonExistent", "READY"));
    }

    @Test
    void testAddTaskMsg() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试正常添加任务消息
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        
        manager.addTaskMsg("task001", taskMsg1);
        manager.addTaskMsg("task001", taskMsg2);
        
        // 验证消息数量
        assertEquals(2, manager.getTaskMsgCount("task001"));
        
        // 测试获取消息
        TaskMsgEntity retrievedMsg1 = manager.getTaskMsg("task001");
        assertNotNull(retrievedMsg1);
        assertEquals("msg001", retrievedMsg1.getMsgId());
        
        TaskMsgEntity retrievedMsg2 = manager.getTaskMsg("task001");
        assertNotNull(retrievedMsg2);
        assertEquals("msg002", retrievedMsg2.getMsgId());
        
        // 验证消息已消费
        assertEquals(0, manager.getTaskMsgCount("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.addTaskMsg(null, taskMsg1));
        assertThrows(NullPointerException.class, () -> manager.addTaskMsg("task001", null));
        
        // 测试不存在的任务 - 应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> manager.addTaskMsg("nonExistent", taskMsg1));
    }

    @Test
    void testGetTaskMsg() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试获取不存在的消息
        TaskMsgEntity nonExistentMsg = manager.getTaskMsg("task001");
        assertNull(nonExistentMsg);
        
        // 添加消息后获取
        TaskMsgEntity taskMsg = createTestTaskMsg("msg001", "task001");
        manager.addTaskMsg("task001", taskMsg);
        
        TaskMsgEntity retrievedMsg = manager.getTaskMsg("task001");
        assertNotNull(retrievedMsg);
        assertEquals("msg001", retrievedMsg.getMsgId());
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.getTaskMsg(null));
    }

    @Test
    void testGetTask() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 测试正常获取
        TaskEntity retrievedTask = manager.getTask("task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        
        // 测试获取不存在的任务
        TaskEntity nonExistentTask = manager.getTask("nonExistent");
        assertNull(nonExistentTask);
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.getTask(null));
    }

    @Test
    void testContainsTask() {
        // 初始状态
        assertFalse(manager.containsTask("task001"));
        
        // 创建任务后
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        assertTrue(manager.containsTask("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.containsTask(null));
    }

    @Test
    void testGetTaskSeedCount() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 初始状态
        assertEquals(0, manager.getTaskSeedCount("task001"));
        
        // 添加种子后
        manager.addTaskSeed("task001", "seed1");
        manager.addTaskSeed("task001", "seed2");
        assertEquals(2, manager.getTaskSeedCount("task001"));
        
        // 消费种子后
        manager.getTaskSeed("task001");
        assertEquals(1, manager.getTaskSeedCount("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.getTaskSeedCount(null));
    }

    @Test
    void testGetTaskMsgCount() {
        // 先创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 初始状态
        assertEquals(0, manager.getTaskMsgCount("task001"));
        
        // 添加消息后
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        manager.addTaskMsg("task001", taskMsg1);
        manager.addTaskMsg("task001", taskMsg2);
        assertEquals(2, manager.getTaskMsgCount("task001"));
        
        // 消费消息后
        manager.getTaskMsg("task001");
        assertEquals(1, manager.getTaskMsgCount("task001"));
        
        // 测试空参数 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> manager.getTaskMsgCount(null));
    }

    @Test
    void testGetTotalTaskCount() {
        // 初始状态
        assertEquals(0, manager.getTotalTaskCount());
        
        // 创建任务后
        TaskEntity task1 = createTestTask("task001");
        TaskEntity task2 = createTestTask("task002");
        manager.createTask(task1);
        manager.createTask(task2);
        
        assertEquals(2, manager.getTotalTaskCount());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // 创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        
        // 并发添加种子和消息
        int threadCount = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    String seed = "seed_" + threadId + "_" + j;
                    TaskMsgEntity taskMsg = createTestTaskMsg("msg_" + threadId + "_" + j, "task001");
                    
                    manager.addTaskSeed("task001", seed);
                    manager.addTaskMsg("task001", taskMsg);
                }
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
        
        // 验证结果
        assertEquals(threadCount * operationsPerThread, manager.getTaskSeedCount("task001"));
        assertEquals(threadCount * operationsPerThread, manager.getTaskMsgCount("task001"));
    }

    @Test
    void testTaskLifecycle() {
        // 1. 创建任务
        TaskEntity task = createTestTask("task001");
        manager.createTask(task);
        assertEquals("NEW", task.getTaskStatus());
        
        // 2. 添加种子
        manager.addTaskSeed("task001", "seed1");
        manager.addTaskSeed("task001", "seed2");
        assertEquals(2, manager.getTaskSeedCount("task001"));
        
        // 3. 更新状态为准备就绪
        manager.updateTaskStatus("task001", "READY");
        TaskEntity readyTask = manager.getTask("task001");
        assertEquals("READY", readyTask.getTaskStatus());
        
        // 4. 创建任务消息
        TaskMsgEntity taskMsg = createTestTaskMsg("msg001", "task001");
        manager.addTaskMsg("task001", taskMsg);
        assertEquals(1, manager.getTaskMsgCount("task001"));
        
        // 5. 消费种子和消息
        String seed = manager.getTaskSeed("task001");
        assertEquals("seed1", seed);
        
        TaskMsgEntity retrievedMsg = manager.getTaskMsg("task001");
        assertEquals("msg001", retrievedMsg.getMsgId());
        
        // 6. 更新状态为运行中
        manager.updateTaskStatus("task001", "RUNNING");
        TaskEntity runningTask = manager.getTask("task001");
        assertEquals("RUNNING", runningTask.getTaskStatus());
    }
} 