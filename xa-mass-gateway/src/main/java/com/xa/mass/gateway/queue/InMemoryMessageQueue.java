package com.xa.mass.gateway.queue;

// 如果 StoredMessage 不在此包，需要导

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class InMemoryMessageQueue implements MessageQueue<Envelope> { // 修改泛型
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageQueue.class);
    // 使用 BlockingQueue，例LinkedBlockingQueue
    private final BlockingQueue<Envelope> queue; // 修改泛型

    public InMemoryMessageQueue() {
        this.queue = new LinkedBlockingQueue<>(); // 无界队列
    }

    @Override
    public void offer(Envelope message) { // 修改参数类型
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        // 对于 LinkedBlockingQueue 的无参构造（无界），offer 几乎总是立即成功
        // 不需try-catch Exception e, offer 本身不抛出受检异常，只会返回false（对于有界队列且满时
        // BlockingQueue.offer(E e) 声明不抛出异常put(E e) 会抛 InterruptedException
        if (!queue.offer(message)) {
            // 对于无界队列，这理论上不应发生，除非极端内存问题
            // 对于有界队列，这意味着队列已满
            throw new RuntimeException("Failed to offer message to in-memory queue (possibly full if bounded)");
        }
    }

    /**
     * 从队列中获取并移除头部元素，如果队列为空则阻塞等待
     * @return 队列的头部元
     * @throws InterruptedException 如果在等待时线程被中
     */
    public Envelope take() throws InterruptedException { // 修改返回类型
        return queue.take();
    }


    /**
     * 从队列中获取并移除头部元素，如果在指定的等待时间内队列仍然为空，则返null
     * @param timeout 等待时间
     * @param unit 时间单位
     * @return 队列的头部元素，如果在超时前队列为空则返null
     * @throws InterruptedException 如果在等待时线程被中
     */
    @Override
    public Envelope poll(long timeout, TimeUnit unit) throws InterruptedException { // 修改返回类型
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
        return "InMemoryMessageQueue";
    }


}
