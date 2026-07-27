package com.xa.mass.server.workerdelivery;

public final class WorkerDeliveryException extends RuntimeException {

    public enum Kind {
        INVALID,
        UNAVAILABLE
    }

    private final Kind kind;

    private WorkerDeliveryException(
            Kind kind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = kind;
    }

    public static WorkerDeliveryException invalid(String message) {
        return new WorkerDeliveryException(Kind.INVALID, message, null);
    }

    public static WorkerDeliveryException unavailable(Throwable cause) {
        return new WorkerDeliveryException(
                Kind.UNAVAILABLE,
                "Worker Delivery Redis is unavailable",
                cause
        );
    }

    public Kind kind() {
        return kind;
    }
}
