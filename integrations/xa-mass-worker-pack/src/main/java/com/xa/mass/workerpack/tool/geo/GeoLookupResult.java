package com.xa.mass.workerpack.tool.geo;

import java.util.LinkedHashMap;
import java.util.Map;

public record GeoLookupResult(
        String query,
        String city,
        String countryCode,
        String timeZone,
        String currency,
        double latitude,
        double longitude,
        String provider,
        boolean simulated
) {
    public Map<String, Object> toOutput() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("city", city);
        data.put("countryCode", countryCode);
        data.put("timeZone", timeZone);
        data.put("currency", currency);
        data.put("latitude", latitude);
        data.put("longitude", longitude);
        data.put("provider", provider);
        data.put("simulated", simulated);
        return data;
    }
}
