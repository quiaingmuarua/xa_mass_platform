package com.xa.mass.worker;

public enum WorkerTransportMode {
    POLLING,
    WEBSOCKET;

    static WorkerTransportMode parse(String value) {
        return switch (value) {
            case "polling" -> POLLING;
            case "websocket" -> WEBSOCKET;
            default -> throw new IllegalArgumentException(
                    "--transport must be polling or websocket"
            );
        };
    }
}
