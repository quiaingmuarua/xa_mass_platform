package com.xa.mass.server.schedule;

/**
 * 通用队列服务接口
 * @param <T> 队列中元素的类型
 */
public interface QueueService<T> {

    /**
     *向队列中添加元素
     * @param item 要添加的元素
     * @throws InterruptedException 如果线程在等待时被中断
     */
    void enqueue(T item) throws InterruptedException;

    /**
     * 从队列中取出元素（阻塞）
     * @return 队列中的元素
     * @throws InterruptedException 如果线程在等待时被中断
     */
    T dequeue() throws InterruptedException;

    /**
     * 获取当前队列大小
     * @return 队列中待处理元素的数量
     */
    int size();

    /**
     * 检查队列是否为空
     * @return 如果队列为空则返回 true，否则返回 false
     */
    boolean isEmpty();
}