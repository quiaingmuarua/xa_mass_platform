package com.xa.mass.gateway.dispatcher.handler;

/**
 * Immutable gateway handler resolution result.
 */
public final class ResolutionResult {

    private final Status status;
    private final MassMessageHandler handler;
    private final String project;
    private final String messageType;
    private final String subMessageType;
    private final String resolutionPath;

    private ResolutionResult(Status status,
                             MassMessageHandler handler,
                             String project,
                             String messageType,
                             String subMessageType,
                             String resolutionPath) {
        this.status = status;
        this.handler = handler;
        this.project = project;
        this.messageType = messageType;
        this.subMessageType = subMessageType;
        this.resolutionPath = resolutionPath;
    }

    public static ResolutionResult found(MassMessageHandler handler,
                                         String project,
                                         String messageType,
                                         String subMessageType,
                                         String resolutionPath) {
        return new ResolutionResult(Status.FOUND, handler, project, messageType, subMessageType, resolutionPath);
    }

    public static ResolutionResult notFound(String project, String messageType, String subMessageType) {
        return new ResolutionResult(Status.NOT_FOUND, null, project, messageType, subMessageType, "none");
    }

    public Status getStatus() {
        return status;
    }

    public MassMessageHandler getHandler() {
        return handler;
    }

    public String getProject() {
        return project;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getSubMessageType() {
        return subMessageType;
    }

    public String getResolutionPath() {
        return resolutionPath;
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    public boolean isNotFound() {
        return status == Status.NOT_FOUND;
    }

    @Override
    public String toString() {
        return "ResolutionResult{"
                + "status=" + status
                + ", project='" + project + '\''
                + ", messageType='" + messageType + '\''
                + ", subMessageType='" + subMessageType + '\''
                + ", resolutionPath='" + resolutionPath + '\''
                + '}';
    }

    public enum Status {
        FOUND,
        NOT_FOUND
    }
}
