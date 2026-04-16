package com.xa.mass.base.channel.tranporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example transporter with priority-separated in-memory queues.
 *
 * <p>This implementation is mainly useful for experiments and demos where the
 * caller wants to model multiple priority lanes without bringing in an
 * external queueing system.
 *
 * @param <T> message type
 */
public class MultiLevelMessageTransporter<T> implements MessageTransporter<T> {

    private static final Logger logger = LoggerFactory.getLogger(MultiLevelMessageTransporter.class);

    private final BlockingQueue<T> highPriorityInputQueue;
    private final BlockingQueue<T> normalPriorityInputQueue;
    private final BlockingQueue<T> lowPriorityInputQueue;

    private final BlockingQueue<T> highPriorityOutputQueue;
    private final BlockingQueue<T> normalPriorityOutputQueue;
    private final BlockingQueue<T> lowPriorityOutputQueue;

    private final AtomicInteger inputProcessed = new AtomicInteger(0);
    private final AtomicInteger outputProcessed = new AtomicInteger(0);

    public MultiLevelMessageTransporter() {
        this.highPriorityInputQueue = new PriorityBlockingQueue<>();
        this.highPriorityOutputQueue = new PriorityBlockingQueue<>();

        this.normalPriorityInputQueue = new LinkedBlockingQueue<>();
        this.lowPriorityInputQueue = new LinkedBlockingQueue<>();
        this.normalPriorityOutputQueue = new LinkedBlockingQueue<>();
        this.lowPriorityOutputQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public void sendInput(T message) {
        MessagePriority priority = getMessagePriority(message);
        switch (priority) {
            case HIGH:
                highPriorityInputQueue.offer(message);
                logger.debug("Enqueued high-priority input message: {}", message);
                break;
            case NORMAL:
                normalPriorityInputQueue.offer(message);
                logger.debug("Enqueued normal-priority input message: {}", message);
                break;
            case LOW:
                lowPriorityInputQueue.offer(message);
                logger.debug("Enqueued low-priority input message: {}", message);
                break;
            default:
                break;
        }
    }

    @Override
    public T receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < endTime) {
            T message = highPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("Dequeued high-priority input message: {}", message);
                return message;
            }

            message = normalPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("Dequeued normal-priority input message: {}", message);
                return message;
            }

            message = lowPriorityInputQueue.poll();
            if (message != null) {
                inputProcessed.incrementAndGet();
                logger.debug("Dequeued low-priority input message: {}", message);
                return message;
            }

            Thread.sleep(10);
        }

        return null;
    }

    @Override
    public void sendOutput(T message) {
        MessagePriority priority = getMessagePriority(message);
        switch (priority) {
            case HIGH:
                highPriorityOutputQueue.offer(message);
                logger.debug("Enqueued high-priority output message: {}", message);
                break;
            case NORMAL:
                normalPriorityOutputQueue.offer(message);
                logger.debug("Enqueued normal-priority output message: {}", message);
                break;
            case LOW:
                lowPriorityOutputQueue.offer(message);
                logger.debug("Enqueued low-priority output message: {}", message);
                break;
            default:
                break;
        }
    }

    @Override
    public T receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < endTime) {
            T message = highPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("Dequeued high-priority output message: {}", message);
                return message;
            }

            message = normalPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("Dequeued normal-priority output message: {}", message);
                return message;
            }

            message = lowPriorityOutputQueue.poll();
            if (message != null) {
                outputProcessed.incrementAndGet();
                logger.debug("Dequeued low-priority output message: {}", message);
                return message;
            }

            Thread.sleep(10);
        }

        return null;
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
     * Resolve the priority lane for a message.
     */
    private MessagePriority getMessagePriority(T message) {
        // Placeholder strategy. Real callers can extend this transporter with
        // domain-specific priority classification if needed.
        return MessagePriority.NORMAL;
    }

    /**
     * Return detailed queue statistics for diagnostics.
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
     * Priority lanes used by the transporter.
     */
    public enum MessagePriority {
        HIGH, NORMAL, LOW
    }
}
