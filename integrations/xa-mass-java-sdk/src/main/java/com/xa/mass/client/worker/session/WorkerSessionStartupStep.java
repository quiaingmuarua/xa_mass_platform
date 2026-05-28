package com.xa.mass.client.worker.session;

public enum WorkerSessionStartupStep {
    REGISTER_ADAPTER_NODE,
    BIND_NODE_GROUP,
    REGISTER_WORKER,
    ONLINE,
    REPORT_CAPABILITY,
    REPORT_STATE,
    START_HEARTBEAT,
    START_POLL
}
