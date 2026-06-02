package com.xa.mass.workerpack.tool.geo;

import java.util.Map;
import java.util.Objects;

public final class GeoLookupTool {
    public static final String EVENT_CODE = "tool.geo.lookup";
    public static final String PROVIDER = DeterministicGeoLookupProvider.PROVIDER_ID;
    private static final GeoLookupProvider DEFAULT_PROVIDER = new DeterministicGeoLookupProvider();

    private GeoLookupTool() {
    }

    public static GeoLookupProvider defaultProvider() {
        return DEFAULT_PROVIDER;
    }

    public static Map<String, Object> lookup(String rawQuery) {
        return lookup(rawQuery, DEFAULT_PROVIDER);
    }

    public static Map<String, Object> lookup(String rawQuery, GeoLookupProvider provider) {
        GeoLookupProvider resolvedProvider = Objects.requireNonNull(provider, "provider is required");
        return resolvedProvider.lookup(new GeoLookupRequest(rawQuery)).toOutput();
    }
}
