package com.xa.mass.worker.execution;

public final class UnknownWorkerEventException extends Exception {

    public UnknownWorkerEventException(String eventCode) {
        super("Unknown Worker event: " + eventCode);
    }
}
