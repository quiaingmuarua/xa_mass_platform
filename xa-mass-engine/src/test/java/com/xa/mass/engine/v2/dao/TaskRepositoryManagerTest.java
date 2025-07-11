package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskRepositoryManager 测试类 - 纯数据操作测试
 */
public class TaskRepositoryManagerTest {

    private TaskRepositoryManager manager;
    private MessageMap<String, TaskEntity> taskMap;

    @BeforeEach
    void setUp() {
        taskMap = new InMemoryMessageMap<>();
        manager = new TaskRepositoryManager(taskMap, QueueProviderType.IN_MEMORY);
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
        assertThrows(NullPointerException.class, () -> new TaskRepositoryManager(null, null));
    }

    @Test
    void testSaveTask() {
        // 测试正常保存任务
        TaskEntity task = createTestTask("task001");
        manager.saveTask(task);
        
        // 验证任务已保存
        TaskEntity retrievedTask = manager.getTask("task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        assertEquals("Test Task task001", retrievedTask.getTaskName());
        assertEquals("PROJECT_A", retrievedTask.getProject());
        
        // 验证任务存在检查
        assertTrue(manager.containsTask("task001"));
    }

    @Test
    void testCreateSeedQueue() {
        // 测试创建种子队列
        manager.createSeedQueue("task001");
        
        // 验证种子队列已创建
        assertEquals(0, manager.getSeedCount("task001"));
    }

    @Test
    void testCreateMsgQueue() {
        // 测试创建消息队列
        manager.createMsgQueue("task001");
        
        // 验证消息队列已创建
        assertEquals(0, manager.getMsgCount("task001"));
    }

    @Test
    void testAddSeed() {
        // 先创建种子队列
        manager.createSeedQueue("task001");
        
        // 测试正常添加种子
        manager.addSeed("task001", "seed1");
        manager.addSeed("task001", "seed2");
        
        // 验证种子数量
        assertEquals(2, manager.getSeedCount("task001"));
        
        // 测试获取种子
        String seed1 = manager.pollSeed("task001");
        assertEquals("seed1", seed1);
        
        String seed2 = manager.pollSeed("task001");
        assertEquals("seed2", seed2);
        
        // 验证种子已消费
        assertEquals(0, manager.getSeedCount("task001"));
    }

    @Test
    void testAddSeeds() {
        // 先创建种子队列
        manager.createSeedQueue("task001");
        
        // 测试批量添加种子
        String[] seeds = {"seed1", "seed2", "seed3"};
        manager.addSeeds("task001", seeds);
        
        // 验证种子数量
        assertEquals(3, manager.getSeedCount("task001"));
        
        // 测试获取种子
        String seed1 = manager.pollSeed("task001");
        assertEquals("seed1", seed1);
        
        String seed2 = manager.pollSeed("task001");
        assertEquals("seed2", seed2);
        
        String seed3 = manager.pollSeed("task001");
        assertEquals("seed3", seed3);
    }

    @Test
    void testPollSeed() {
        // 先创建种子队列
        manager.createSeedQueue("task001");
        
        // 测试获取不存在的种子
        String nonExistentSeed = manager.pollSeed("task001");
        assertNull(nonExistentSeed);
        
        // 添加种子后获取
        manager.addSeed("task001", "seed1");
        String seed = manager.pollSeed("task001");
        assertEquals("seed1", seed);
    }

    @Test
    void testAddMsg() {
        // 先创建消息队列
        manager.createMsgQueue("task001");
        
        // 测试正常添加任务消息
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        
        manager.addMsg("task001", taskMsg1);
        manager.addMsg("task001", taskMsg2);
        
        // 验证消息数量
        assertEquals(2, manager.getMsgCount("task001"));
        
        // 测试获取消息
        TaskMsgEntity retrievedMsg1 = manager.pollMsg("task001");
        assertNotNull(retrievedMsg1);
        assertEquals("msg001", retrievedMsg1.getMsgId());
        
        TaskMsgEntity retrievedMsg2 = manager.pollMsg("task001");
        assertNotNull(retrievedMsg2);
        assertEquals("msg002", retrievedMsg2.getMsgId());
        
        // 验证消息已消费
        assertEquals(0, manager.getMsgCount("task001"));
    }

    @Test
    void testPollMsg() {
        // 先创建消息队列
        manager.createMsgQueue("task001");
        
        // 测试获取不存在的消息
        TaskMsgEntity nonExistentMsg = manager.pollMsg("task001");
        assertNull(nonExistentMsg);
        
        // 添加消息后获取
        TaskMsgEntity taskMsg = createTestTaskMsg("msg001", "task001");
        manager.addMsg("task001", taskMsg);
        
        TaskMsgEntity retrievedMsg = manager.pollMsg("task001");
        assertNotNull(retrievedMsg);
        assertEquals("msg001", retrievedMsg.getMsgId());
    }

    @Test
    void testGetTask() {
        // 先保存任务
        TaskEntity task = createTestTask("task001");
        manager.saveTask(task);
        
        // 测试正常获取
        TaskEntity retrievedTask = manager.getTask("task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        
        // 测试获取不存在的任务
        TaskEntity nonExistentTask = manager.getTask("nonExistent");
        assertNull(nonExistentTask);
    }

    @Test
    void testContainsTask() {
        // 初始状态
        assertFalse(manager.containsTask("task001"));
        
        // 保存任务后
        TaskEntity task = createTestTask("task001");
        manager.saveTask(task);
        assertTrue(manager.containsTask("task001"));
    }

    @Test
    void testGetSeedCount() {
        // 先创建种子队列
        manager.createSeedQueue("task001");
        
        // 初始状态
        assertEquals(0, manager.getSeedCount("task001"));
        
        // 添加种子后
        manager.addSeed("task001", "seed1");
        manager.addSeed("task001", "seed2");
        assertEquals(2, manager.getSeedCount("task001"));
        
        // 消费种子后
        manager.pollSeed("task001");
        assertEquals(1, manager.getSeedCount("task001"));
    }

    @Test
    void testGetMsgCount() {
        // 先创建消息队列
        manager.createMsgQueue("task001");
        
        // 初始状态
        assertEquals(0, manager.getMsgCount("task001"));
        
        // 添加消息后
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        manager.addMsg("task001", taskMsg1);
        manager.addMsg("task001", taskMsg2);
        assertEquals(2, manager.getMsgCount("task001"));
        
        // 消费消息后
        manager.pollMsg("task001");
        assertEquals(1, manager.getMsgCount("task001"));
    }

    @Test
    void testGetTotalTaskCount() {
        // 初始状态
        assertEquals(0, manager.getTotalTaskCount());
        
        // 保存任务后
        TaskEntity task1 = createTestTask("task001");
        TaskEntity task2 = createTestTask("task002");
        manager.saveTask(task1);
        manager.saveTask(task2);
        
        assertEquals(2, manager.getTotalTaskCount());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // 创建种子队列和消息队列
        manager.createSeedQueue("task001");
        manager.createMsgQueue("task001");
        
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
                    
                    manager.addSeed("task001", seed);
                    manager.addMsg("task001", taskMsg);
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
        assertEquals(threadCount * operationsPerThread, manager.getSeedCount("task001"));
        assertEquals(threadCount * operationsPerThread, manager.getMsgCount("task001"));
    }

    @Test
    void testTaskLifecycle() {
        // 1. 保存任务
        TaskEntity task = createTestTask("task001");
        manager.saveTask(task);
        assertEquals("NEW", task.getTaskStatus());
        
        // 2. 创建队列
        manager.createSeedQueue("task001");
        manager.createMsgQueue("task001");
        
        // 3. 添加种子
        manager.addSeed("task001", "seed1");
        manager.addSeed("task001", "seed2");
        assertEquals(2, manager.getSeedCount("task001"));
        
        // 4. 添加任务消息
        TaskMsgEntity taskMsg = createTestTaskMsg("msg001", "task001");
        manager.addMsg("task001", taskMsg);
        assertEquals(1, manager.getMsgCount("task001"));
        
        // 5. 消费种子和消息
        String seed = manager.pollSeed("task001");
        assertEquals("seed1", seed);
        
        TaskMsgEntity retrievedMsg = manager.pollMsg("task001");
        assertEquals("msg001", retrievedMsg.getMsgId());
        
        // 6. 验证剩余数量
        assertEquals(1, manager.getSeedCount("task001"));
        assertEquals(0, manager.getMsgCount("task001"));
    }
} 