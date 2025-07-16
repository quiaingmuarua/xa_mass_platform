package com.xa.mass.base.channel.messaging.api;


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
    
    /**
     * 统一的构造方法接口
     * 所有实现类都应该提供这个构造方法
     * @param queueKey 队列键名
     * @param messageType 消息类型Class
     * @param extraParams 扩展参数（可选）
     */
    // 注意：接口中不能定义构造方法，这里只是文档说明
    // 所有实现类都应该提供：MessageQueue(String queueKey, Class<T> messageType, Map<String, String> extraParams)
}
