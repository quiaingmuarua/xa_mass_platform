package com.xa.mass.server.queue;

import java.util.concurrent.ConcurrentLinkedQueue;

public class InMemoryMessageQueue<T> implements MessageQueue<T> {
    private final ConcurrentLinkedQueue<T> queue;

    public InMemoryMessageQueue() {
        this.queue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void offer(T message) {
        queue.offer(message);
    }

    @Override
    public T poll() {
        return queue.poll();
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