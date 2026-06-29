package com.xa.mass.transport.starter;

import com.xa.mass.transport.channel.ResultIngressEntry;

/**
 * Stable source for SDK/starter-owned draining into engine result convergence.
 */
public interface ResultIngressSource {

    ResultIngressEntry poll(long timeoutMillis) throws InterruptedException;
}
