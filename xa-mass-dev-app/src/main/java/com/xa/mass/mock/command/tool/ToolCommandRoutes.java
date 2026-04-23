package com.xa.mass.mock.command.tool;

import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.command.core.CommandDefinition;
import com.xa.mass.command.core.CommandRegistry;
import com.xa.mass.command.model.CommandContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ToolCommandRoutes {

    private static final Map<String, GeoPreset> GEO_PRESETS = Map.of(
            "beijing", new GeoPreset("Beijing", "CN", "Asia/Shanghai", "CNY", 39.9042, 116.4074),
            "shanghai", new GeoPreset("Shanghai", "CN", "Asia/Shanghai", "CNY", 31.2304, 121.4737),
            "new york", new GeoPreset("New York", "US", "America/New_York", "USD", 40.7128, -74.0060),
            "london", new GeoPreset("London", "GB", "Europe/London", "GBP", 51.5074, -0.1278),
            "tokyo", new GeoPreset("Tokyo", "JP", "Asia/Tokyo", "JPY", 35.6762, 139.6503),
            "singapore", new GeoPreset("Singapore", "SG", "Asia/Singapore", "SGD", 1.3521, 103.8198)
    );

    private static final Map<String, BigDecimal> FAKE_BASE_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "CNY", new BigDecimal("7.18"),
            "EUR", new BigDecimal("0.92"),
            "JPY", new BigDecimal("154.30"),
            "GBP", new BigDecimal("0.79"),
            "SGD", new BigDecimal("1.35")
    );

    private ToolCommandRoutes() {
    }

    public static void registerToolRoutes() {
        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.time.now")
                .handler(ToolCommandRoutes::toolTimeNow)
                .resolver(json -> json)
                .summary("Return current time in the requested zone or UTC offset.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.geo.lookup")
                .handler(ToolCommandRoutes::toolGeoLookup)
                .resolver(json -> json)
                .summary("Return a lightweight simulated geo profile for a city/query.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.currency.quote")
                .handler(ToolCommandRoutes::toolCurrencyQuote)
                .resolver(json -> json)
                .summary("Return a simulated currency conversion quote derived from stable fake rates.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());
    }

    private static Map<String, Object> toolTimeNow(JsonObject request, CommandContext context) {
        ZoneId zoneId = resolveZone(request);
        ZonedDateTime now = ZonedDateTime.now(zoneId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zoneId", zoneId.getId());
        data.put("utcOffset", now.getOffset().getId());
        data.put("epochMillis", now.toInstant().toEpochMilli());
        data.put("iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("localDate", now.toLocalDate().toString());
        data.put("localTime", now.toLocalTime().toString());
        data.put("simulated", false);
        return data;
    }

    private static Map<String, Object> toolGeoLookup(JsonObject request, CommandContext context) {
        String query = stringValue(request, "query", stringValue(request, "city", "")).trim();
        if (query.isBlank()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "query or city is required");
        }

        GeoPreset preset = GEO_PRESETS.get(query.toLowerCase(Locale.ROOT));
        if (preset == null) {
            int hash = Math.abs(query.toLowerCase(Locale.ROOT).hashCode());
            double latitude = ((hash % 18000) / 100.0) - 90.0;
            double longitude = (((hash / 18000) % 36000) / 100.0) - 180.0;
            preset = new GeoPreset(
                    query,
                    "ZZ",
                    "UTC",
                    "USD",
                    round(latitude),
                    round(longitude)
            );
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("city", preset.city());
        data.put("countryCode", preset.countryCode());
        data.put("timeZone", preset.timeZone());
        data.put("currency", preset.currency());
        data.put("latitude", preset.latitude());
        data.put("longitude", preset.longitude());
        data.put("provider", "mock-dev-app");
        data.put("simulated", true);
        return data;
    }

    private static Map<String, Object> toolCurrencyQuote(JsonObject request, CommandContext context) {
        String base = stringValue(request, "base", "USD").trim().toUpperCase(Locale.ROOT);
        String target = stringValue(request, "target", "CNY").trim().toUpperCase(Locale.ROOT);
        BigDecimal amount = decimalValue(request, "amount", BigDecimal.ONE);

        BigDecimal baseRate = FAKE_BASE_RATES.getOrDefault(base, fallbackRate(base));
        BigDecimal targetRate = FAKE_BASE_RATES.getOrDefault(target, fallbackRate(target));
        BigDecimal rate = targetRate.divide(baseRate, 6, RoundingMode.HALF_UP);
        BigDecimal convertedAmount = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", base);
        data.put("target", target);
        data.put("amount", amount);
        data.put("rate", rate);
        data.put("convertedAmount", convertedAmount);
        data.put("quotedAt", Instant.now().toString());
        data.put("provider", "mock-dev-app");
        data.put("simulated", true);
        return data;
    }

    private static ZoneId resolveZone(JsonObject request) {
        String zoneId = stringValue(request, "zoneId", "").trim();
        if (!zoneId.isBlank()) {
            try {
                return ZoneId.of(zoneId);
            } catch (Exception e) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "invalid zoneId: " + zoneId);
            }
        }
        String utcOffset = stringValue(request, "utcOffset", "").trim();
        if (!utcOffset.isBlank()) {
            try {
                return ZoneOffset.of(utcOffset);
            } catch (Exception e) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "invalid utcOffset: " + utcOffset);
            }
        }
        return ZoneId.systemDefault();
    }

    private static BigDecimal decimalValue(JsonObject request, String field, BigDecimal defaultValue) {
        if (request == null || !request.has(field) || request.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            return request.get(field).getAsBigDecimal();
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be numeric");
        }
    }

    private static String stringValue(JsonObject json, String field, String defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        return json.get(field).getAsString();
    }

    private static BigDecimal fallbackRate(String code) {
        int hash = Math.abs(code.hashCode());
        BigDecimal base = new BigDecimal((hash % 9000) + 1000);
        return base.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static void registerIfAbsent(CommandDefinition<?, ?> definition) {
        if (!CommandRegistry.contains(definition.getEvent())) {
            CommandRegistry.register(definition);
        }
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
