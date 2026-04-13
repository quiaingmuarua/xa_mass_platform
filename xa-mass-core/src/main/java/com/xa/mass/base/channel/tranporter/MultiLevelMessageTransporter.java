package com.xa.mass.base.channel.tranporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多级队列的消息传输器实现示例
 * 展示如何实现多级队列架构，支持消息优先级和不同级别的处理
 * 
 * @param <T> 消息类型
 */
public class MultiLevelMessageTransporter<T> implements MessageTransporter<T> {

    private static final Logger logger = LoggerFactory.getLogger(MultiLevelMessageTransporter.class);

    // 多级队列定义
    private final BlockingQueue<T> highPriorityInputQueue;    // 高优先级输入队列
    private final BlockingQueue<T> normalPriorityInputQueue;  // 普通优先级输入队列
    private final BlockingQueue<T> lowPriorityInputQueue;     // 低优先级输入队列

    private final BlockingQueue<T> highPriorityOutputQueue;   // 高优先级输出队列
    private final BlockingQueue<T> normalPriorityOutputQueue; // 普通优先级输出队列
    private final BlockingQueue<T> lowPriorityOutputQueue;    // 低优先级输出队列

    // 统计信息
    private final AtomicInteger inputProcessed = new AtomicInteger(0);
    private final AtomicInteger outputProcessed = new AtomicInteger(0);

    public MultiLevelMessageTransporter() {
        // 使用优先级队列实现高优先级队列
        this.highPriorityInputQueue = new PriorityBlockingQueue<>();
        this.highPriorityOutputQueue = new PriorityBlockingQueue<>();

        // 使用普通阻塞队列实现其他级别队列
        this.normalPriorityInputQueue = new LinkedBlockingQueue<>();
        this.lowPriorityInputQueue = new LinkedBlockingQueue<>();
        this.normalPriorityOutputQueue = new LinkedBlockingQueue<>();
        this.lowPriorityOutputQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public void sendInput(T message) {
        // 根据消息优先级选择队列
        MessagePriority priority = getMessagePriority(message);
        switch (priority) {
            case HIGH:
                highPriorityInputQueue.offer(message);
                logger.debug("高优先级输入消息入队: {}", message);
                break;
            case NORMAL:
                normalPriorityInputQueue.offer(message);
                logger.debug("普通优先级输入消息入队: {}", message);
                break;
            case LOW:
                lowPriorityInputQueue.offer(message);
                logger.debug("低优先级输入消息入队: {}", message);
                break;
        }
    }

    @Override
    public T receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < endTime) {
            // 按优先级顺序尝试从队列获取消息
            T message = highPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("从高优先级输入队列获取消息: {}", message);
                return message;
            }

            message = normalPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("从普通优先级输入队列获取消息: {}", message);
                return message;
            }

            message = lowPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("从低优先级输入队列获取消息: {}", message);
                return message;
            }

            // 如果所有队列都为空，等待一段时间再重试
            Thread.sleep(10);
        }

        return null; // 超时返回 null
    }

    @Override
    public void sendOutput(T message) {
        // 根据消息优先级选择队列
        MessagePriority priority = getMessagePriority(message);
        switch (priority) {
            case HIGH:
                highPriorityOutputQueue.offer(message);
                logger.debug("高优先级输出消息入队: {}", message);
                break;
            case NORMAL:
                normalPriorityOutputQueue.offer(message);
                logger.debug("普通优先级输出消息入队: {}", message);
                break;
            case LOW:
                lowPriorityOutputQueue.offer(message);
                logger.debug("低优先级输出消息入队: {}", message);
                break;
        }
    }

    @Override
    public T receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < endTime) {
            // 按优先级顺序尝试从队列获取消息
            T message = highPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("从高优先级输出队列获取消息: {}", message);
                return message;
            }

            message = normalPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("从普通优先级输出队列获取消息: {}", message);
                return message;
            }

            message = lowPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("从低优先级输出队列获取消息: {}", message);
                return message;
            }

            // 如果所有队列都为空，等待一段时间再重试
            Thread.sleep(10);
        }

        return null; // 超时返回 null
    }

    @Override
    public int inputQueueSize() {
        return highPriorityInputQueue.size() + normalPriorityInputQueue.size() + lowPriorityInputQueue.size();
    }

    @Override
    public int outputQueueSize() {
        return highPriorityOutputQueue.size() + normalPriorityOutputQueue.size() + lowPriorityOutputQueue.size();
    }

    /**
     * 获取消息优先级
     * 可以根据消息类型、设备ID、项目等确定优先级
     */
    private MessagePriority getMessagePriority(T message) {
        // 示例：根据消息类型确定优先级
        // 注意：这里需要根据具体的消息类型来实现优先级判断逻辑
        // 可能需要添加一个接口或使用反射来获取消息的优先级信息
        
        // 默认普通优先级
        return MessagePriority.NORMAL;
    }

    /**
     * 获取详细的队列统计信息
     */
    public String getDetailedStats() {
        return String.format(
                "MultiLevelQueue Stats - Input: High=%d, Normal=%d, Low=%d, Total=%d; " +
                        "Output: High=%d, Normal=%d, Low=%d, Total=%d; " +
                        "Processed: Input=%d, Output=%d",
                highPriorityInputQueue.size(), normalPriorityInputQueue.size(), lowPriorityInputQueue.size(), inputQueueSize(),
                highPriorityOutputQueue.size(), normalPriorityOutputQueue.size(), lowPriorityOutputQueue.size(), outputQueueSize(),
                inputProcessed.get(), outputProcessed.get()
        );
    }

    /**
     * 消息优先级枚举
     */
    public enum MessagePriority {
        HIGH, NORMAL, LOW
    }
} 