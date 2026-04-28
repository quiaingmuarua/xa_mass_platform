package com.xa.mass.base.channel.tranporter;

import com.xa.mass.base.channel.messaging.api.MessageQueue;

import java.util.concurrent.TimeUnit;

/**
 * Queue-backed transporter with distinct input/output message types.
 */
public class QueueBasedMessageTransporter<I, O> implements MessageTransporter<I, O> {

    private final MessageQueue<I> inputQueue;
    private final MessageQueue<O> outputQueue;

    public QueueBasedMessageTransporter(MessageQueue<I> inputQueue, MessageQueue<O> outputQueue) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
    }

    @Override
    public void sendInput(I message) {
        inputQueue.offer(message);
    }

    @Override
    public I receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        return inputQueue.poll(timeout, unit);
    }

    @Override
    public void sendOutput(O message) {
        outputQueue.offer(message);
    }

    @Override
    public O receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        return outputQueue.poll(timeout, unit);
    }

    @Override
    public int inputQueueSize() {
        return inputQueue.size();
    }

    @Override
    public int outputQueueSize() {
        return outputQueue.size();
    }

    public MessageQueue<I> getInputQueue() {
        return inputQueue;
    }

    public MessageQueue<O> getOutputQueue() {
        return outputQueue;
    }
}
