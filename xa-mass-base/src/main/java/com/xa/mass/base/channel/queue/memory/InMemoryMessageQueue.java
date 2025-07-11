package com.xa.mass.base.channel.queue.memory;

import com.xa.mass.base.channel.queue.api.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 内存消息队列实现
 * 基于 LinkedBlockingQueue 的泛型消息队列
 * 
 * @param <T> 消息类型
 */
public class InMemoryMessageQueue<T> implements MessageQueue<T> {
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageQueue.class);
    
    // 使用 BlockingQueue，例如 LinkedBlockingQueue
    private final BlockingQueue<T> queue;
    private final String name;

    public InMemoryMessageQueue() {
        this("InMemoryMessageQueue");
    }

    public InMemoryMessageQueue(String name) {
        this.queue = new LinkedBlockingQueue<>(); // 无界队列
        this.name = name != null ? name : "InMemoryMessageQueue";
    }

    @Override
    public void offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        // 对于 LinkedBlockingQueue 的无参构造（无界），offer 几乎总是立即成功
        // 不需要 try-catch Exception，offer 本身不抛出受检异常，只会返回 false（对于有界队列且满时）
        // BlockingQueue.offer(E e) 声明不抛出异常，put(E e) 会抛 InterruptedException
        if (!queue.offer(message)) {
            // 对于无界队列，这理论上不应发生，除非极端内存问题
            // 对于有界队列，这意味着队列已满
            throw new RuntimeException("Failed to offer message to in-memory queue (possibly full if bounded)");
        }
    }

    /**
     * 从队列中获取并移除头部元素，如果队列为空则阻塞等待
     * @return 队列的头部元素
     * @throws InterruptedException 如果在等待时线程被中断
     */
    public T take() throws InterruptedException {
        return queue.take();
    }

    /**
     * 从队列中获取并移除头部元素，如果在指定的等待时间内队列仍然为空，则返回 null
     * @param timeout 等待时间
     * @param unit 时间单位
     * @return 队列的头部元素，如果在超时前队列为空则返回 null
     * @throws InterruptedException 如果在等待时线程被中断
     */
    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        log.debug("InMemoryMessageQueue SIZE start get");
        return queue.size();
    }

    @Override
    public String getName() {
        return name;
    }
}
