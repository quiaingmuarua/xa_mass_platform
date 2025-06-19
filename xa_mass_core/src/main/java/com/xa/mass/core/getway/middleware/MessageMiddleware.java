package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.queue.Envelope;

@FunctionalInterface
public interface MessageMiddleware {
    boolean handle(Envelope envelope);
}
