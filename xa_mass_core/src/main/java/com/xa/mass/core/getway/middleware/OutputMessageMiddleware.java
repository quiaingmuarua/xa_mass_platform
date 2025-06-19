package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.queue.Envelope;

@FunctionalInterface
public interface OutputMessageMiddleware {
    boolean handle(Envelope envelope);
}