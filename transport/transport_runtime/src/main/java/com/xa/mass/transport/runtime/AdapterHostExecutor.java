package com.xa.mass.transport.runtime;

import java.util.concurrent.Future;

/**
 * Narrow adapter-facing executor view.
 */
@FunctionalInterface
public interface AdapterHostExecutor {

    Future<?> submit(Runnable task);
}
