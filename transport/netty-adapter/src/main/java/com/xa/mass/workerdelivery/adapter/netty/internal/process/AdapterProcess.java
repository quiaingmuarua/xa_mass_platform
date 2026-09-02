package com.xa.mass.workerdelivery.adapter.netty.internal.process;

/** Repository-internal lifecycle contract for one resident Adapter process. */
public interface AdapterProcess {

    void runLoop();

    void quiesce();

    void finishAfterLoopStop();
}
