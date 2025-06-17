package com.xa.mass.core.queue;


import java.util.concurrent.TimeUnit;

public interface MessageQueue<T> {
    void offer(T message);

    /**
     * 从队列中获取并移除头部元素，如果在指定的等待时间内队列仍然为空，则返null
     * @param timeout 等待时间
     * @param unit 时间单位
     * @return 队列的头部元素，如果在超时前队列为空则返null
     * @throws InterruptedException 如果在等待时线程被中
     */
    T poll(long timeout, TimeUnit unit) throws InterruptedException; // 新增带超时的方法

    boolean isEmpty();

    int size();


    String getName();
}
