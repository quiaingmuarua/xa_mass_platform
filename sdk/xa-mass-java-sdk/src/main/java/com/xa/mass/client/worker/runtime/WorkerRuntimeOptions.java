package com.xa.mass.client.worker.runtime;

import java.util.concurrent.ScheduledExecutorService;

record WorkerRuntimeOptions(WorkerRuntimeListener listener, ScheduledExecutorService executor) {
    WorkerRuntimeOptions {
        listener = listener == null ? WorkerRuntimeListener.NOOP : listener;
    }
}
