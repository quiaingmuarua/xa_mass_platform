package com.xa.mass.workerpack.tool.geo;

public record GeoLookupRequest(String query) {
    public GeoLookupRequest {
        query = query == null ? "" : query.trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query or city is required");
        }
    }
}
