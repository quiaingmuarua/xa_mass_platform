package com.xa.mass.server.taskdata;

public final class TaskDataException extends RuntimeException {

    public enum Kind {
        INVALID,
        NOT_FOUND,
        UNAVAILABLE
    }

    private final Kind kind;

    private TaskDataException(
            Kind kind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.kind = kind;
    }

    public static TaskDataException invalid(String message) {
        return new TaskDataException(Kind.INVALID, message, null);
    }

    public static TaskDataException notFound(String message) {
        return new TaskDataException(Kind.NOT_FOUND, message, null);
    }

    public static TaskDataException unavailable(Throwable cause) {
        return new TaskDataException(
                Kind.UNAVAILABLE,
                "Task data Redis is unavailable",
                cause
        );
    }

    public Kind kind() {
        return kind;
    }
}
