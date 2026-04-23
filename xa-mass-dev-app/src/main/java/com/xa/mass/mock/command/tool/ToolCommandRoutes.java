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
import java.util.List;
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

    private static final Map<String, CountryPreset> COUNTRY_PRESETS = Map.of(
            "CN", new CountryPreset("CN", "China", "Beijing", "+86", "Asia/Shanghai"),
            "GB", new CountryPreset("GB", "United Kingdom", "London", "+44", "Europe/London"),
            "US", new CountryPreset("US", "United States", "Washington, D.C.", "+1", "America/New_York"),
            "JP", new CountryPreset("JP", "Japan", "Tokyo", "+81", "Asia/Tokyo"),
            "SG", new CountryPreset("SG", "Singapore", "Singapore", "+65", "Asia/Singapore")
    );

    private ToolCommandRoutes() {
    }

    public static List<CommandDefinition<JsonObject, Map<String, Object>>> definitions() {
        return List.of(
                CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.time.now")
                        .handler(ToolCommandRoutes::toolTimeNow)
                        .resolver(json -> json)
                        .summary("Return current time in the requested zone or UTC offset.")
                        .suggestedPhases("prepare", "verify")
                        .safeForScenario(true)
                        .build(),
                CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.geo.lookup")
                        .handler(ToolCommandRoutes::toolGeoLookup)
                        .resolver(json -> json)
                        .summary("Return a lightweight simulated geo profile for a city/query.")
                        .suggestedPhases("prepare", "verify")
                        .safeForScenario(true)
                        .build(),
                CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.currency.quote")
                        .handler(ToolCommandRoutes::toolCurrencyQuote)
                        .resolver(json -> json)
                        .summary("Return a simulated currency conversion quote derived from stable fake rates.")
                        .suggestedPhases("prepare", "verify")
                        .safeForScenario(true)
                        .build(),
                CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.country.capital.lookup")
                        .handler(ToolCommandRoutes::toolCountryCapitalLookup)
                        .resolver(json -> json)
                        .summary("Resolve a country code to a stable country/capital reference profile.")
                        .suggestedPhases("prepare", "verify")
                        .safeForScenario(true)
                        .build(),
                CommandDefinition.<JsonObject, Map<String, Object>>builder("tool.phone.country.detect")
                        .handler(ToolCommandRoutes::toolPhoneCountryDetect)
                        .resolver(json -> json)
                        .summary("Detect a phone number country from common international dial-code prefixes.")
                        .suggestedPhases("prepare", "verify")
                        .safeForScenario(true)
                        .build()
        );
    }

    public static void registerToolRoutes() {
        definitions().forEach(ToolCommandRoutes::registerIfAbsent);
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

    private static Map<String, Object> toolCountryCapitalLookup(JsonObject request, CommandContext context) {
        String countryCode = stringValue(request, "countryCode", "").trim().toUpperCase(Locale.ROOT);
        if (countryCode.isBlank()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "countryCode is required");
        }

        CountryPreset preset = COUNTRY_PRESETS.get(countryCode);
        if (preset == null) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "unsupported countryCode: " + countryCode);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("countryCode", countryCode);
        data.put("countryName", preset.countryName());
        data.put("capital", preset.capital());
        data.put("dialCode", preset.dialCode());
        data.put("timeZone", preset.timeZone());
        data.put("provider", "mock-dev-app");
        data.put("simulated", false);
        return data;
    }

    private static Map<String, Object> toolPhoneCountryDetect(JsonObject request, CommandContext context) {
        String phoneNumber = stringValue(request, "phoneNumber", "").trim();
        if (phoneNumber.isBlank()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "phoneNumber is required");
        }

        String normalized = phoneNumber.replaceAll("[^+\\d]", "");
        CountryPreset preset = detectCountryByPhone(normalized);
        if (preset == null) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "unsupported phoneNumber prefix");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phoneNumber", phoneNumber);
        data.put("normalizedPhoneNumber", normalized);
        data.put("countryCode", preset.countryCode());
        data.put("countryName", preset.countryName());
        data.put("dialCode", preset.dialCode());
        data.put("timeZone", preset.timeZone());
        data.put("provider", "mock-dev-app");
        data.put("simulated", false);
        return data;
    }

    private static CountryPreset detectCountryByPhone(String normalizedPhoneNumber) {
        if (normalizedPhoneNumber.startsWith("+86")) {
            return presetWithCode("CN");
        }
        if (normalizedPhoneNumber.startsWith("+44")) {
            return presetWithCode("GB");
        }
        if (normalizedPhoneNumber.startsWith("+1")) {
            return presetWithCode("US");
        }
        if (normalizedPhoneNumber.startsWith("+81")) {
            return presetWithCode("JP");
        }
        if (normalizedPhoneNumber.startsWith("+65")) {
            return presetWithCode("SG");
        }
        return null;
    }

    private static CountryPreset presetWithCode(String countryCode) {
        CountryPreset preset = COUNTRY_PRESETS.get(countryCode);
        if (preset == null) {
            throw new IllegalStateException("missing country preset: " + countryCode);
        }
        return preset;
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

    private record CountryPreset(
            String countryCode,
            String countryName,
            String capital,
            String dialCode,
            String timeZone
    ) {
    }
}
