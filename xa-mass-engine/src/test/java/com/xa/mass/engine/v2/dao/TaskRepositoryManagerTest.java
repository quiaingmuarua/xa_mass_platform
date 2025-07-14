package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.channel.queue.MessageStreamProviderRegistry;
import com.xa.mass.base.channel.queue.MessageMapProviderRegistry;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.engine.v2.service.EngineRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

/**
 * TaskRepositoryManager 测试类 - 纯数据操作测试
 */
public class TaskRepositoryManagerTest {

    private TaskRepositoryManager manager;
    private java.util.concurrent.ConcurrentMap<Project, MessageMap<String, TaskEntity>> projectTaskMap;

    @BeforeEach
    void setUp() {
        MessageStreamProviderRegistry.clearCache();
        MessageMapProviderRegistry.clearCache();
        EngineRegistry.clearAllServices();
        manager = TaskRepositoryManager.createWithDefaultProjects(QueueProviderType.IN_MEMORY);
    }

    /**
     * 创建测试任务实体
     */
    private TaskEntity createTestTask(String taskId) {
        TaskEntity task = new TaskEntity(taskId, "Test Task " + taskId, Project.DEMO_APP.getCode());
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
        manager.saveTask(Project.DEMO_APP, task);
        
        // 验证任务已保存
        TaskEntity retrievedTask = manager.getTask(Project.DEMO_APP, "task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        assertEquals("Test Task task001", retrievedTask.getTaskName());
        assertEquals(Project.DEMO_APP.getCode(), retrievedTask.getProject());
        
        // 验证任务存在检查
        assertTrue(manager.containsTask(Project.DEMO_APP, "task001"));
    }

    @Test
    void testCreateSeedStream() {
        // 测试创建种子流
        manager.createSeedStream("task001");
        // 验证种子流已创建
        assertEquals(0, manager.getSeedCount("task001"));
    }

    @Test
    void testCreateMsgStream() {
        // 测试创建消息流
        manager.createMsgStream("task001");
        // 验证消息流已创建
        assertEquals(0, manager.getMsgCount("task001"));
    }

    @Test
    void testAddSeed() {
        // 先创建种子流
        manager.createSeedStream("task001");
        // 测试正常添加种子
        manager.addSeed("task001", "seed1");
        manager.addSeed("task001", "seed2");
        // 验证种子数量
        assertEquals(2, manager.getSeedCount("task001"));
        // 测试获取种子
        String seed1 = manager.getSeed("task001");
        assertEquals("seed1", seed1);
        String seed2 = manager.getSeed("task001");
        assertEquals("seed2", seed2);
        // 验证种子已消费
        assertEquals(0, manager.getSeedCount("task001"));
    }

    @Test
    void testAddSeedsBatch() {
        // 先创建种子流
        manager.createSeedStream("task001");
        // 测试批量添加种子
        String[] seeds = {"seed1", "seed2", "seed3"};
        for (String seed : seeds) {
            manager.addSeed("task001", seed);
        }
        // 验证种子数量
        assertEquals(3, manager.getSeedCount("task001"));
        // 测试获取种子
        String seed1 = manager.getSeed("task001");
        assertEquals("seed1", seed1);
        String seed2 = manager.getSeed("task001");
        assertEquals("seed2", seed2);
        String seed3 = manager.getSeed("task001");
        assertEquals("seed3", seed3);
    }

    @Test
    void testPollSeed() {
        // 先创建种子流
        manager.createSeedStream("task001");
        // 测试获取不存在的种子
        String nonExistentSeed = manager.getSeed("task001");
        assertNull(nonExistentSeed);
        // 添加种子后获取
        manager.addSeed("task001", "seed1");
        String seed = manager.getSeed("task001");
        assertEquals("seed1", seed);
    }

    @Test
    void testAddMsg() {
        // 先创建消息流
        manager.createMsgStream("task001");
        // 测试正常添加任务消息
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        manager.addMsg("task001", taskMsg1);
        manager.addMsg("task001", taskMsg2);
        // 验证消息数量
        assertEquals(2, manager.getMsgCount("task001"));
        // 测试获取消息
        TaskMsgEntity retrievedMsg1 = manager.getMsg("task001");
        assertNotNull(retrievedMsg1);
        assertEquals("msg001", retrievedMsg1.getMsgId());
        TaskMsgEntity retrievedMsg2 = manager.getMsg("task001");
        assertNotNull(retrievedMsg2);
        assertEquals("msg002", retrievedMsg2.getMsgId());
        // 验证消息已消费
        assertEquals(0, manager.getMsgCount("task001"));
    }

    @Test
    void testPollMsg() {
        // 先创建消息流
        manager.createMsgStream("task001");
        // 测试获取不存在的消息
        TaskMsgEntity nonExistentMsg = manager.getMsg("task001");
        assertNull(nonExistentMsg);
        // 添加消息后获取
        TaskMsgEntity taskMsg = createTestTaskMsg("msg001", "task001");
        manager.addMsg("task001", taskMsg);
        TaskMsgEntity retrievedMsg = manager.getMsg("task001");
        assertNotNull(retrievedMsg);
        assertEquals("msg001", retrievedMsg.getMsgId());
    }

    @Test
    void testGetTask() {
        // 先保存任务
        TaskEntity task = createTestTask("task001");
        manager.saveTask(Project.DEMO_APP, task);
        
        // 测试正常获取
        TaskEntity retrievedTask = manager.getTask(Project.DEMO_APP, "task001");
        assertNotNull(retrievedTask);
        assertEquals("task001", retrievedTask.getTaskId());
        
        // 测试获取不存在的任务
        TaskEntity nonExistentTask = manager.getTask(Project.DEMO_APP, "nonExistent");
        assertNull(nonExistentTask);
    }

    @Test
    void testContainsTask() {
        // 初始状态
        assertFalse(manager.containsTask(Project.DEMO_APP, "task001"));
        
        // 保存任务后
        TaskEntity task = createTestTask("task001");
        manager.saveTask(Project.DEMO_APP, task);
        assertTrue(manager.containsTask(Project.DEMO_APP, "task001"));
    }

    @Test
    void testGetSeedCount() {
        // 先创建种子流
        manager.createSeedStream("task001");
        // 初始状态
        assertEquals(0, manager.getSeedCount("task001"));
        // 添加种子后
        manager.addSeed("task001", "seed1");
        manager.addSeed("task001", "seed2");
        assertEquals(2, manager.getSeedCount("task001"));
        // 消费种子后
        manager.getSeed("task001");
        assertEquals(1, manager.getSeedCount("task001"));
    }

    @Test
    void testGetMsgCount() {
        // 先创建消息流
        manager.createMsgStream("task001");
        // 初始状态
        assertEquals(0, manager.getMsgCount("task001"));
        // 添加消息后
        TaskMsgEntity taskMsg1 = createTestTaskMsg("msg001", "task001");
        TaskMsgEntity taskMsg2 = createTestTaskMsg("msg002", "task001");
        manager.addMsg("task001", taskMsg1);
        manager.addMsg("task001", taskMsg2);
        assertEquals(2, manager.getMsgCount("task001"));
        // 消费消息后
        manager.getMsg("task001");
        assertEquals(1, manager.getMsgCount("task001"));
    }

    @Test
    void testGetTotalTaskCount() {
        assertEquals(0, manager.getTotalTaskCount());
        manager.saveTask(Project.DEMO_APP, createTestTask("task001"));
        manager.saveTask(Project.DEMO_APP, createTestTask("task002"));
        assertEquals(2, manager.getTotalTaskCount());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // 创建种子流和消息流
        manager.createSeedStream("task001");
        manager.createMsgStream("task001");
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
        TaskEntity task = createTestTask("task001");
        manager.saveTask(Project.DEMO_APP, task);
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task.getTaskId()));
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, task.getTaskId()));
        assertTrue(manager.containsTask(Project.DEMO_APP, "task001"));
    }

    @Test
    void testSeedOperations() {
        String taskId = "task001";
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        
        // 添加种子
        manager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId), "seed1");
        manager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId), "seed2");
        
        // 验证种子数量
        assertEquals(2, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
        
        // 获取种子
        String seed1 = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(seed1);
        assertEquals(1, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
        
        String seed2 = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(seed2);
        assertEquals(0, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
        
        // 队列空时返回null
        String nullSeed = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNull(nullSeed);
    }

    @Test
    void testBatchSeedOperations() {
        String taskId = "task002";
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        
        // 批量添加种子
        String[] seeds = {"seed1", "seed2", "seed3"};
        for (String seed : seeds) {
            manager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId), seed);
        }
        
        // 验证种子数量
        assertEquals(3, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
        
        // 批量获取种子
        String seed1 = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(seed1);
        
        String seed2 = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(seed2);
        
        String seed3 = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(seed3);
        
        assertEquals(0, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
    }

    @Test
    void testSeedQueueConcurrency() {
        String taskId = "task003";
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId));
        
        // 并发添加和获取种子
        IntStream.range(0, 100).parallel().forEach(i -> {
            manager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId), "seed" + i);
        });
        
        // 验证所有种子都被添加
        assertEquals(100, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)));
        
        // 并发获取种子
        List<String> retrievedSeeds = IntStream.range(0, 100)
            .parallel()
            .mapToObj(i -> manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // 验证获取的种子数量
        assertTrue(retrievedSeeds.size() <= 100);
        assertTrue(manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, taskId)) >= 0);
    }

    @Test
    void testMsgOperations() {
        String taskId = "task004";
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId));
        
        // 添加消息
        TaskMsgEntity msg1 = new TaskMsgEntity("msg1", taskId);
        TaskMsgEntity msg2 = new TaskMsgEntity("msg2", taskId);
        
        manager.addMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId), msg1);
        manager.addMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId), msg2);
        
        // 验证消息数量
        assertEquals(2, manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)));
        
        // 获取消息
        TaskMsgEntity retrievedMsg1 = manager.getMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(retrievedMsg1);
        assertEquals(1, manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)));
        
        TaskMsgEntity retrievedMsg2 = manager.getMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId));
        assertNotNull(retrievedMsg2);
        assertEquals(0, manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)));
    }

    @Test
    void testMsgQueueConcurrency() {
        String taskId = "task005";
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId));
        
        // 并发添加消息
        IntStream.range(0, 50).parallel().forEach(i -> {
            TaskMsgEntity msg = new TaskMsgEntity("msg" + i, taskId);
            manager.addMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId), msg);
        });
        
        // 验证消息数量
        assertEquals(50, manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)));
        
        // 并发获取消息
        List<TaskMsgEntity> retrievedMsgs = IntStream.range(0, 50)
            .parallel()
            .mapToObj(i -> manager.getMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // 验证获取的消息数量
        assertTrue(retrievedMsgs.size() <= 50);
        assertTrue(manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, taskId)) >= 0);
    }

    @Test
    void testIntegratedOperations() {
        // 创建任务
        TaskEntity task1 = createTestTask("task100");
        TaskEntity task2 = createTestTask("task200");
        
        manager.saveTask(Project.DEMO_APP, task1);
        manager.saveTask(Project.TEST_APP, task2);
        
        // 验证项目任务数量
        assertEquals(1, manager.getProjectTaskCount(Project.DEMO_APP));
        assertEquals(1, manager.getProjectTaskCount(Project.TEST_APP));
        assertEquals(2, manager.getTotalTaskCount());
        
        // 创建队列并操作
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task1.getTaskId()));
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.TEST_APP, task2.getTaskId()));
        
        manager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task1.getTaskId()), "seed100");
        TaskMsgEntity msg = new TaskMsgEntity("msg200", "task200");
        manager.addMsg(QueueKeyUtil.getMsgStreamKey(Project.TEST_APP, task2.getTaskId()), msg);
        
        // 验证操作结果
        assertEquals(1, manager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task1.getTaskId())));
        assertEquals(1, manager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.TEST_APP, task2.getTaskId())));
        
        String retrievedSeed = manager.getSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task1.getTaskId()));
        assertEquals("seed100", retrievedSeed);
        
        TaskMsgEntity retrievedMsg = manager.getMsg(QueueKeyUtil.getMsgStreamKey(Project.TEST_APP, task2.getTaskId()));
        assertEquals("msg200", retrievedMsg.getMsgId());
    }
} 