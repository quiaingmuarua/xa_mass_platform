package com.xa.mass.core.middleware;

import com.xa.mass.core.queue.Envelope;

@FunctionalInterface
public interface OutputMessageMiddleware {
    boolean handle(Envelope envelope);
}