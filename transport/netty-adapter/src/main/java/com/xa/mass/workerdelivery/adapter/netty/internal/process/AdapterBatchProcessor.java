package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import java.util.List;

/** Processes one already-acquired Adapter batch. */
@FunctionalInterface
public interface AdapterBatchProcessor<T> {

    BatchProcessResult process(List<T> batch);
}
