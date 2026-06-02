package com.xa.mass.workerpack.tool.geo;

public final class GeoLookupProviderException extends RuntimeException {
    private final String errorCode;

    public GeoLookupProviderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode == null || errorCode.isBlank() ? "GEO_PROVIDER_FAILURE" : errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
