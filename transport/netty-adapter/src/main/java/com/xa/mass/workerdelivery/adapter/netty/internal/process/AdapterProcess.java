package com.xa.mass.workerdelivery.adapter.netty.internal.process;

/** Repository-internal lifecycle contract for one scheduled Adapter process. */
public interface AdapterProcess {

    void round();

    void quiesce();

    void finishAfterSchedulerStop();
}
