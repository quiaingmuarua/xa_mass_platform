package com.xa.mass.base.channel.tranporter;

import java.util.concurrent.TimeUnit;

/**
 * Transporter abstraction for adapter-local input/output queues.
 *
 * @param <I> input message type
 * @param <O> output message type
 */
public interface MessageTransporter<I, O> {

    void sendInput(I message);

    I receiveInput(long timeout, TimeUnit unit) throws InterruptedException;

    void sendOutput(O message);

    O receiveOutput(long timeout, TimeUnit unit) throws InterruptedException;

    int inputQueueSize();

    int outputQueueSize();
}
