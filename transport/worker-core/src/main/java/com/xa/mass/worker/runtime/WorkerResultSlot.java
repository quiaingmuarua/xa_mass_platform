package com.xa.mass.worker.runtime;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.util.Objects;

final class WorkerResultSlot implements AutoCloseable {

    private WorkerResult result;
    private boolean closed;

    synchronized boolean offer(WorkerResult value) {
        Objects.requireNonNull(value, "result");
        if (closed || result != null) {
            return false;
        }
        result = value;
        return true;
    }

    synchronized WorkerResult peek() {
        return closed ? null : result;
    }

    synchronized void clearIfSame(WorkerResult expected) {
        if (!closed && result == expected) {
            result = null;
        }
    }

    synchronized boolean hasResult() {
        return !closed && result != null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        result = null;
    }
}
