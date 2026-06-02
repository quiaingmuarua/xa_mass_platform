package com.xa.mass.workerpack.tool.geo;

public interface GeoLookupProvider {
    String providerId();

    GeoLookupResult lookup(GeoLookupRequest request);
}
