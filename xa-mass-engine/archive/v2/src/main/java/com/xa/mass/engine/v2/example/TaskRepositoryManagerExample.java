package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

import java.util.Arrays;
import java.util.List;

/**
 * TaskRepositoryManager使用MessageStream的示例
 */
public class TaskRepositoryManagerExample {
    
    public static void main(String[] args) {
        // 示例1: 使用内存流
        memoryStreamDemo();
        
        // 示例2: 批量操作演示
        batchOperationDemo();
        
        // 示例3: 统计信息演示
        statsDemo();
    }
    
    /**
     * 示例1: 使用内存流的基本操作
     */
    private static void memoryStreamDemo() {
        System.out.println("=== 示例1: 内存流基本操作 ===");
        
        // 创建使用内存流的TaskRepositoryManager
        TaskRepositoryManager manager = TaskRepositoryManager.createWithDefaultProjects(MessageProviderType.IN_MEMORY);
        
        // 创建任务
        TaskEntity task = new TaskEntity();
        task.setTaskId("task-001");
        task.setProject(Project.DEMO_APP.getCode());
        task.setTaskName("测试任务");
        
        // 保存任务
        manager.saveTask(Project.DEMO_APP, task);
        System.out.println("保存任务: " + task.getTaskId());
        
        // 创建种子流和消息流
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        
        // 添加种子
        manager.addSeed("task-001", "https://example.com/1");
        manager.addSeed("task-001", "https://example.com/2");
        manager.addSeed("task-001", "https://example.com/3");
        System.out.println("添加了3个种子，当前种子数量: " + manager.getSeedCount("task-001"));
        
        // 消费种子
        String seed1 = manager.getSeed("task-001");
        String seed2 = manager.getSeed("task-001");
        System.out.println("消费种子: " + seed1 + ", " + seed2);
        System.out.println("剩余种子数量: " + manager.getSeedCount("task-001"));
        
        // 添加消息
        TaskMsgEntity msg1 = new TaskMsgEntity("msg-001", "task-001");
        msg1.setTaskMsgStatus("INIT");
        
        TaskMsgEntity msg2 = new TaskMsgEntity("msg-002", "task-001");
        msg2.setTaskMsgStatus("INIT");
        
        manager.addMsg("task-001", msg1);
        manager.addMsg("task-001", msg2);
        System.out.println("添加了2个消息，当前消息数量: " + manager.getMsgCount("task-001"));
        
        // 消费消息
        TaskMsgEntity consumedMsg = manager.getMsg("task-001");
        System.out.println("消费消息: " + (consumedMsg != null ? consumedMsg.getMsgId() : "null"));
        System.out.println("剩余消息数量: " + manager.getMsgCount("task-001"));
    }
    
    /**
     * 示例2: 批量操作演示
     */
    private static void batchOperationDemo() {
        System.out.println("\n=== 示例2: 批量操作演示 ===");
        
        TaskRepositoryManager manager = TaskRepositoryManager.createWithDefaultProjects(MessageProviderType.IN_MEMORY);
        
        // 创建任务和流
        TaskEntity task = new TaskEntity();
        task.setTaskId("task-002");
        task.setProject(Project.DEMO_APP.getCode());
        manager.saveTask(Project.DEMO_APP, task);
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        
        // 批量添加种子
        List<String> seeds = Arrays.asList(
            "https://example.com/batch1",
            "https://example.com/batch2",
            "https://example.com/batch3",
            "https://example.com/batch4",
            "https://example.com/batch5"
        );
        manager.addSeedsBatch("task-002", seeds);
        System.out.println("批量添加了" + seeds.size() + "个种子，当前种子数量: " + manager.getSeedCount("task-002"));
        
        // 批量消费种子
        List<String> consumedSeeds = manager.getSeedsBatch("task-002", 3);
        System.out.println("批量消费了" + consumedSeeds.size() + "个种子: " + consumedSeeds);
        System.out.println("剩余种子数量: " + manager.getSeedCount("task-002"));
        
        // 批量添加消息
        List<TaskMsgEntity> msgs = Arrays.asList(
            createTaskMsg("task-002", "批量消息1"),
            createTaskMsg("task-002", "批量消息2"),
            createTaskMsg("task-002", "批量消息3")
        );
        manager.addMsgsBatch("task-002", msgs);
        System.out.println("批量添加了" + msgs.size() + "个消息，当前消息数量: " + manager.getMsgCount("task-002"));
        
        // 批量消费消息
        List<TaskMsgEntity> consumedMsgs = manager.getMsgsBatch("task-002", 2);
        System.out.println("批量消费了" + consumedMsgs.size() + "个消息: " + 
            consumedMsgs.stream().map(TaskMsgEntity::getMsgId).toList());
        System.out.println("剩余消息数量: " + manager.getMsgCount("task-002"));
    }
    
    /**
     * 示例3: 统计信息演示
     */
    private static void statsDemo() {
        System.out.println("\n=== 示例3: 统计信息演示 ===");
        
        TaskRepositoryManager manager = TaskRepositoryManager.createWithDefaultProjects(MessageProviderType.IN_MEMORY);
        
        // 创建任务和流
        TaskEntity task = new TaskEntity();
        task.setTaskId("task-003");
        task.setProject(Project.DEMO_APP.getCode());
        manager.saveTask(Project.DEMO_APP, task);
        manager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        manager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.valueOf(task.getProject()), task.getTaskId()));
        
        // 添加一些数据
        manager.addSeed("task-003", "https://example.com/stats1");
        manager.addSeed("task-003", "https://example.com/stats2");
        
        TaskMsgEntity msg = new TaskMsgEntity("msg-003", "task-003");
        manager.addMsg("task-003", msg);
        
        // 获取统计信息
        MessageStream.StreamStats seedStats = manager.getSeedStreamStats("task-003");
        MessageStream.StreamStats msgStats = manager.getMsgStreamStats("task-003");
        
        System.out.println("种子流统计: " + seedStats);
        System.out.println("消息流统计: " + msgStats);
        
        // 消费一些数据后再次查看统计
        manager.getSeed("task-003");
        manager.getMsg("task-003");
        
        seedStats = manager.getSeedStreamStats("task-003");
        msgStats = manager.getMsgStreamStats("task-003");
        
        System.out.println("消费后种子流统计: " + seedStats);
        System.out.println("消费后消息流统计: " + msgStats);
        
        // 清理过期消息
        int cleanedSeeds = manager.cleanupExpiredSeeds("task-003");
        int cleanedMsgs = manager.cleanupExpiredMsgs("task-003");
        System.out.println("清理了" + cleanedSeeds + "个过期种子，" + cleanedMsgs + "个过期消息");
    }
    
    /**
     * 创建TaskMsgEntity的辅助方法
     */
    private static TaskMsgEntity createTaskMsg(String taskId, String msgId) {
        TaskMsgEntity msg = new TaskMsgEntity(msgId, taskId);
        msg.setTaskMsgStatus("INIT");
        return msg;
    }
} 