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
 */
public class MultiLevelMessageTransporter<I, O> implements MessageTransporter<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(MultiLevelMessageTransporter.class);

    private final BlockingQueue<I> highPriorityInputQueue;
    private final BlockingQueue<I> normalPriorityInputQueue;
    private final BlockingQueue<I> lowPriorityInputQueue;

    private final BlockingQueue<O> highPriorityOutputQueue;
    private final BlockingQueue<O> normalPriorityOutputQueue;
    private final BlockingQueue<O> lowPriorityOutputQueue;

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
    public void sendInput(I message) {
        MessagePriority priority = getInputPriority(message);
        switch (priority) {
            case HIGH -> highPriorityInputQueue.offer(message);
            case NORMAL -> normalPriorityInputQueue.offer(message);
            case LOW -> lowPriorityInputQueue.offer(message);
        }
        logger.debug("Enqueued input message with priority {}", priority);
    }

    @Override
    public I receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            I message = pollInput();
            if (message != null) {
                inputProcessed.incrementAndGet();
                return message;
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Override
    public void sendOutput(O message) {
        MessagePriority priority = getOutputPriority(message);
        switch (priority) {
            case HIGH -> highPriorityOutputQueue.offer(message);
            case NORMAL -> normalPriorityOutputQueue.offer(message);
            case LOW -> lowPriorityOutputQueue.offer(message);
        }
        logger.debug("Enqueued output message with priority {}", priority);
    }

    @Override
    public O receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            O message = pollOutput();
            if (message != null) {
                outputProcessed.incrementAndGet();
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

    public String getDetailedStats() {
        return String.format(
                "MultiLevelQueue Stats - Input: High=%d, Normal=%d, Low=%d, Total=%d; Output: High=%d, Normal=%d, Low=%d, Total=%d; Processed: Input=%d, Output=%d",
                highPriorityInputQueue.size(),
                normalPriorityInputQueue.size(),
                lowPriorityInputQueue.size(),
                inputQueueSize(),
                highPriorityOutputQueue.size(),
                normalPriorityOutputQueue.size(),
                lowPriorityOutputQueue.size(),
                outputQueueSize(),
                inputProcessed.get(),
                outputProcessed.get()
        );
    }

    private I pollInput() {
        I message = highPriorityInputQueue.poll();
        if (message != null) {
            return message;
        }
        message = normalPriorityInputQueue.poll();
        if (message != null) {
            return message;
        }
        return lowPriorityInputQueue.poll();
    }

    private O pollOutput() {
        O message = highPriorityOutputQueue.poll();
        if (message != null) {
            return message;
        }
        message = normalPriorityOutputQueue.poll();
        if (message != null) {
            return message;
        }
        return lowPriorityOutputQueue.poll();
    }

    private MessagePriority getInputPriority(I message) {
        return MessagePriority.NORMAL;
    }

    private MessagePriority getOutputPriority(O message) {
        return MessagePriority.NORMAL;
    }

    public enum MessagePriority {
        HIGH, NORMAL, LOW
    }
}
