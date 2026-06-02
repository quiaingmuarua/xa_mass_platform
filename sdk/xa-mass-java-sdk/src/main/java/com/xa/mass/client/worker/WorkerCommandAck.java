package com.xa.mass.client.worker;

public record WorkerCommandAck(String status, String reason) {
    public static WorkerCommandAck deliveryAccepted(String reason) {
        return new WorkerCommandAck("DELIVERY_ACCEPTED", reason);
    }

    public static WorkerCommandAck failed(String reason) {
        return new WorkerCommandAck("FAILED", reason);
    }
}
