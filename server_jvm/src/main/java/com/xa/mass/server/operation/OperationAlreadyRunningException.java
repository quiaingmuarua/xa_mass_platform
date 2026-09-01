package com.xa.mass.server.operation;

public final class OperationAlreadyRunningException
        extends RuntimeException {

    private final String namespace;
    private final String resourceId;

    OperationAlreadyRunningException(
            String namespace,
            String resourceId
    ) {
        super("Operation is already running");
        this.namespace = namespace;
        this.resourceId = resourceId;
    }

    public String namespace() {
        return namespace;
    }

    public String resourceId() {
        return resourceId;
    }
}
