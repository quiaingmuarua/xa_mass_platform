package com.xa.mass.server.schedule;


import com.xa.mass.server.handler.OutboundMessage;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * OutboundQueue 模拟一个用于发送任务结果或通知的队列。
 * 实现了 QueueService 接口，后续可以替换为 Redis 或其他中间件实现。
 */
public class OutboundQueue implements QueueService<OutboundMessage> {

    private final BlockingQueue<OutboundMessage> taskQueue;

    // 使用单例模式确保只有一个队列实例
    private static final OutboundQueue INSTANCE = new OutboundQueue();

    private OutboundQueue() {
        // 初始化内部队列，这里使用 LinkedBlockingQueue，可以根据需求选择其他 BlockingQueue 实现
        this.taskQueue = new LinkedBlockingQueue<>();
    }

    public static OutboundQueue getInstance() {
        return INSTANCE;
    }

    /**
     * 向队列中添加任务（例如，任务结果或通知）
     * @param item 要发送的消息字符串
     * @throws InterruptedException 如果线程在等待时被中断
     */
    @Override
    public void enqueue(OutboundMessage item) throws InterruptedException {
        taskQueue.put(item);
    }

    /**
     * 从队列中取出任务（阻塞），用于后续处理（例如，发送给客户端）
     * @return 队列中的消息字符串
     * @throws InterruptedException 如果线程在等待时被中断
     */
    @Override
    public OutboundMessage dequeue() throws InterruptedException {
        return taskQueue.take();
    }

    /**
     * 获取当前队列中待处理任务的数量
     * @return 队列大小
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
}