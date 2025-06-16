package com.xa.mass.server.queue;

public interface MessageQueue<T> {
    void offer(T message);

    T poll();

    boolean isEmpty();

    int size();
} 