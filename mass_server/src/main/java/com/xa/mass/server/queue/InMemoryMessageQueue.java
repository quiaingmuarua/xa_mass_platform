package com.xa.mass.server.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit; // 如果需要带超时的 poll

public class InMemoryMessageQueue<T> implements MessageQueue<T> {
    // 使用 BlockingQueue，例如 LinkedBlockingQueue
    private final BlockingQueue<T> queue;

    public InMemoryMessageQueue() {
        // 你可以指定队列的容量，如果需要有界队列
        // this.queue = new LinkedBlockingQueue<>(capacity);
        this.queue = new LinkedBlockingQueue<>(); // 无界队列
    }

    @Override
    public void offer(T message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        try {
            //对于有界队列，offer 可能会阻塞，或者使用 put(message) 阻塞式添加
            //对于 LinkedBlockingQueue 的无参构造（无界），offer 几乎总是立即成功
            queue.offer(message);
        } catch (Exception e) {
            // 处理 offer 失败的情况，例如队列已满（对于有界队列）
            // 或者在 offer 过程中线程被中断
            // 对于 LinkedBlockingQueue 的无界情况，这里不太可能发生
            Thread.currentThread().interrupt(); // 保持中断状态
            throw new RuntimeException("Failed to offer message to queue", e);
        }
    }

    /**
     * 从队列中获取并移除头部元素，如果队列为空则阻塞等待。
     * @return 队列的头部元素
     * @throws InterruptedException 如果在等待时线程被中断
     */
    public T take() throws InterruptedException {
        // take() 方法会在队列为空时阻塞，直到有元素可用
        return queue.take();
    }

    /**
     * 从队列中获取并移除头部元素，如果队列为空则立即返回 null。
     * 这是 MessageQueue 接口定义的方法。
     * @return 队列的头部元素，如果为空则返回 null
     */
    @Override
    public T poll() {
        // BlockingQueue 的 poll() 方法是非阻塞的，如果队列为空则返回 null
        return queue.poll();
    }

    /**
     * 从队列中获取并移除头部元素，如果在指定的等待时间内队列仍然为空，则返回 null。
     * @param timeout 等待时间
     * @param unit 时间单位
     * @return 队列的头部元素，如果在超时前队列为空则返回 null
     * @throws InterruptedException 如果在等待时线程被中断
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }


    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        return queue.size();
    }
}