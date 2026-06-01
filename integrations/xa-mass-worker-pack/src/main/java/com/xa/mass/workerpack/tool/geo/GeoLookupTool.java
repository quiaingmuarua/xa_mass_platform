package com.xa.mass.workerpack.tool.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class GeoLookupTool {
    public static final String EVENT_CODE = "tool.geo.lookup";
    public static final String PROVIDER = "worker-pack-geo";

    private static final Map<String, GeoPreset> GEO_PRESETS = Map.of(
            "beijing", new GeoPreset("Beijing", "CN", "Asia/Shanghai", "CNY", 39.9042, 116.4074),
            "shanghai", new GeoPreset("Shanghai", "CN", "Asia/Shanghai", "CNY", 31.2304, 121.4737),
            "new york", new GeoPreset("New York", "US", "America/New_York", "USD", 40.7128, -74.0060),
            "london", new GeoPreset("London", "GB", "Europe/London", "GBP", 51.5074, -0.1278),
            "tokyo", new GeoPreset("Tokyo", "JP", "Asia/Tokyo", "JPY", 35.6762, 139.6503),
            "singapore", new GeoPreset("Singapore", "SG", "Asia/Singapore", "SGD", 1.3521, 103.8198)
    );

    private GeoLookupTool() {
    }

    public static Map<String, Object> lookup(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query or city is required");
        }

        GeoPreset preset = GEO_PRESETS.get(query.toLowerCase(Locale.ROOT));
        if (preset == null) {
            int hash = Math.abs(query.toLowerCase(Locale.ROOT).hashCode());
            double latitude = ((hash % 18000) / 100.0) - 90.0;
            double longitude = (((hash / 18000) % 36000) / 100.0) - 180.0;
            preset = new GeoPreset(query, "ZZ", "UTC", "USD", round(latitude), round(longitude));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("city", preset.city());
        data.put("countryCode", preset.countryCode());
        data.put("timeZone", preset.timeZone());
        data.put("currency", preset.currency());
        data.put("latitude", preset.latitude());
        data.put("longitude", preset.longitude());
        data.put("provider", PROVIDER);
        data.put("simulated", true);
        return data;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private record GeoPreset(
            String city,
            String countryCode,
            String timeZone,
            String currency,
            double latitude,
            double longitude
    ) {
    }
}
