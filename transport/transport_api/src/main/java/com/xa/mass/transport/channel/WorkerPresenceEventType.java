package com.xa.mass.transport.channel;

/**
 * Session-presence facts observed by a concrete worker transport.
 */
public enum WorkerPresenceEventType {
    CONNECTED,
    HEARTBEAT,
    DISCONNECTED
}
