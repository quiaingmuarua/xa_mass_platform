package com.xa.mass.server.schedule;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TaskInboundQueue 模拟一个用于接收客户端任务的队列。
 * 实现了 QueueService 接口，后续可以替换为 Redis 或其他中间件实现。
 */
public class TaskInboundQueue implements QueueService<String> {

    private final BlockingQueue<String> taskQueue;

    // 使用单例模式确保只有一个队列实例
    private static final TaskInboundQueue INSTANCE = new TaskInboundQueue();

    private TaskInboundQueue() {
        this.taskQueue = new LinkedBlockingQueue<>();
    }

    public static TaskInboundQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 向队列中添加任务
     * @param task 消息字符串（可以为 JSON 格式）
     */
    @Override
    public void enqueue(String task) throws InterruptedException {
        taskQueue.put(task);
    }

    /**
     * 从队列中取出任务（阻塞）
     * @return 任务字符串
     */
    @Override
    public String dequeue() throws InterruptedException {
        return taskQueue.take();
    }

    /**
     * 获取当前队列大小
     * @return 队列中待处理任务数量
     */
    @Override
    public int size() {
        return taskQueue.size();
    }

    /**
     * 检查队列是否为空
     * @return 如果队列为空则返回 true，否则返回 false
     */
    @Override
    public boolean isEmpty() {
        return taskQueue.isEmpty();
    }

    // 为了保持原有静态方法的兼容性，可以保留它们，并让它们调用实例方法
    // 但推荐逐渐过渡到使用实例方法

    /**
     * @deprecated 推荐使用 {@link #getInstance()#enqueue(String)}
     * 向队列中添加任务
     * @param task 消息字符串（可以为 JSON 格式）
     */
    @Deprecated
    public static void addTask(String task) {
        try {
            getInstance().enqueue(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 考虑日志记录或抛出自定义运行时异常
        }
    }

    /**
     * @deprecated 推荐使用 {@link #getInstance()#dequeue()}
     * 从队列中取出任务（阻塞）
     * @return 任务字符串
     */
    @Deprecated
    public static String takeTask() {
        try {
            return getInstance().dequeue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 考虑日志记录或返回特定错误指示
            return null;
        }
    }

    /**
     * @deprecated 推荐使用 {@link #getInstance()#size()}
     * 获取当前队列大小
     * @return 队列中待处理任务数量
     */
    @Deprecated
    public static int getQueueSize() {
        return getInstance().size();
    }
}