package com.xa.mass.server.api.v1.model;

public record TaskResultsExportNotReadyResponse(String status) {

    public static final String STATUS = "not_ready";

    public TaskResultsExportNotReadyResponse() {
        this(STATUS);
    }
}
