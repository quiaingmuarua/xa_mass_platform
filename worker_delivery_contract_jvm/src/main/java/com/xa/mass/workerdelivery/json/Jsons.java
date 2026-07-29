package com.xa.mass.workerdelivery.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Jsons {

    private static final Gson ENGINE = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Jsons() {
    }

    public static Map<String, Object> parseObject(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must be present");
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException(
                        "json contains trailing content"
                );
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(
                        "json must contain an object"
                );
            }
            return convertObject(parsed.getAsJsonObject());
        } catch (JsonParseException | IOException error) {
            throw new IllegalArgumentException("json is malformed", error);
        }
    }

    public static String toJson(Object value) {
        return ENGINE.toJson(requireJsonValue(value));
    }

    private static Map<String, Object> convertObject(JsonObject value) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            converted.put(entry.getKey(), convert(entry.getValue()));
        }
        return converted;
    }

    private static List<Object> convertArray(JsonArray value) {
        List<Object> converted = new ArrayList<>(value.size());
        for (JsonElement item : value) {
            converted.add(convert(item));
        }
        return converted;
    }

    private static Object convert(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            return convertObject(value.getAsJsonObject());
        }
        if (value.isJsonArray()) {
            return convertArray(value.getAsJsonArray());
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        String lexical = value.getAsString();
        if (isIntegralLexical(lexical)) {
            try {
                return Long.valueOf(lexical);
            } catch (NumberFormatException ignored) {
                return new BigDecimal(lexical);
            }
        }
        return new BigDecimal(lexical);
    }

    private static Object requireJsonValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            requireFiniteNumber((Number) value);
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                converted.put(
                        (String) entry.getKey(),
                        requireJsonValue(entry.getValue())
                );
            }
            return converted;
        }
        if (value instanceof Collection<?>) {
            List<Object> converted = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                converted.add(requireJsonValue(item));
            }
            return converted;
        }
        throw new IllegalArgumentException(
                "Unsupported JSON value type: " + value.getClass().getName()
        );
    }

    private static void requireFiniteNumber(Number value) {
        if (value instanceof Double
                && !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(
                    "JSON numbers must be finite"
            );
        }
        if (value instanceof Float
                && !Float.isFinite(value.floatValue())) {
            throw new IllegalArgumentException(
                    "JSON numbers must be finite"
            );
        }
    }

    private static boolean isIntegralLexical(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
