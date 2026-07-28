package com.xa.mass.worker;

public enum WorkerTransportMode {
    POLLING,
    WEBSOCKET,
    SOCKET;

    static WorkerTransportMode parse(String value) {
        return switch (value) {
            case "polling" -> POLLING;
            case "websocket" -> WEBSOCKET;
            case "socket" -> SOCKET;
            default -> throw new IllegalArgumentException(
                    "--transport must be polling, websocket, or socket"
            );
        };
    }
}
