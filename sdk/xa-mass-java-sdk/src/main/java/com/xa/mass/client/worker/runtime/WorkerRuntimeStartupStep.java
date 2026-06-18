package com.xa.mass.client.worker.runtime;

public enum WorkerRuntimeStartupStep {
    ONLINE,
    START_HEARTBEAT,
    START_POLL,
    CONNECT_WEBSOCKET,
    START_RESULT_SENDER
}
