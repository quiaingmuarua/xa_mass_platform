package com.xa.mass.client.worker.session;

public enum WorkerSessionStartupStep {
    REGISTER_WORKER,
    ONLINE,
    START_HEARTBEAT,
    START_POLL,
    CONNECT_WEBSOCKET,
    START_RESULT_SENDER
}
