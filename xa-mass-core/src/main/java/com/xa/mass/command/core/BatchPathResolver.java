package com.xa.mass.command.core;

import com.google.gson.*;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BatchPathResolver {

    private BatchPathResolver() {
    }

    static Object resolveContextReference(String reference, Map<String, Object> context) {
        if (reference == null || !reference.startsWith("$ctx.")) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "invalid context reference: " + reference);
        }
        return resolvePath(context, reference.substring("$ctx.".length()), "context");
    }

    static Object resolveResultReference(String reference, Object resultData) {
        if (reference == null || !reference.startsWith("$result")) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "invalid result reference: " + reference);
        }
        String path = "$result".equals(reference) ? "" : reference.substring("$result.".length());
        Object root = normalizeRoot(resultData);
        if (path.isEmpty()) {
            return normalizeScalarValue(root);
        }
        return resolvePath(root, path, "result");
    }

    static boolean isScalarValue(Object value) {
        Object normalized = normalizeScalarValue(value);
        return normalized == null
                || normalized instanceof String
                || normalized instanceof Number
                || normalized instanceof Boolean;
    }

    static Object normalizeScalarValue(Object value) {
        return value instanceof JsonNull ? null : value;
    }

    static Object toPlainValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                values.add(toPlainValue(item));
            }
            return values;
        }
        JsonObject object = element.getAsJsonObject();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), toPlainValue(entry.getValue()));
        }
        return values;
    }

    private static Object normalizeRoot(Object value) {
        return normalizeScalarValue(value);
    }

    private static Object resolvePath(Object root, String path, String scopeName) {
        Object current = root;
        for (PathToken token : parse(path)) {
            if (token.property != null) {
                current = readProperty(current, token.property, scopeName, path);
            }
            for (Integer index : token.indexes) {
                current = readIndex(current, index, scopeName, path);
            }
        }
        return normalizeScalarValue(current);
    }

    private static Object readProperty(Object current, String property, String scopeName, String path) {
        if (current == null) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "null encountered while resolving " + scopeName + " path: " + path);
        }
        if (current instanceof JsonObject json) {
            if (!json.has(property)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "missing field '" + property + "' in " + scopeName + " path: " + path);
            }
            return toPlainValue(json.get(property));
        }
        if (current instanceof Map<?, ?> map) {
            if (!map.containsKey(property)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "missing field '" + property + "' in " + scopeName + " path: " + path);
            }
            return map.get(property);
        }
        throw new CommandException(ErrorCode.PARSE_ERROR, "cannot read field '" + property + "' from " + current.getClass().getSimpleName());
    }

    private static Object readIndex(Object current, int index, String scopeName, String path) {
        if (current == null) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "null encountered while resolving " + scopeName + " path: " + path);
        }
        if (index < 0) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "negative index is not supported in " + scopeName + " path: " + path);
        }
        if (current instanceof JsonArray array) {
            if (index >= array.size()) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "index out of bounds in " + scopeName + " path: " + path);
            }
            return toPlainValue(array.get(index));
        }
        if (current instanceof List<?> list) {
            if (index >= list.size()) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "index out of bounds in " + scopeName + " path: " + path);
            }
            return list.get(index);
        }
        if (current.getClass().isArray()) {
            if (index >= Array.getLength(current)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "index out of bounds in " + scopeName + " path: " + path);
            }
            return Array.get(current, index);
        }
        throw new CommandException(ErrorCode.PARSE_ERROR, "cannot index into " + current.getClass().getSimpleName());
    }

    private static List<PathToken> parse(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "path is empty");
        }
        String[] segments = path.split("\\.");
        List<PathToken> tokens = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.trim().isEmpty()) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "invalid empty path segment: " + path);
            }
            tokens.add(parseSegment(segment));
        }
        return tokens;
    }

    private static PathToken parseSegment(String segment) {
        StringBuilder property = new StringBuilder();
        List<Integer> indexes = new ArrayList<>();
        int cursor = 0;
        while (cursor < segment.length()) {
            char ch = segment.charAt(cursor);
            if (ch == '[') {
                int close = segment.indexOf(']', cursor);
                if (close <= cursor + 1) {
                    throw new CommandException(ErrorCode.PARSE_ERROR, "invalid array index segment: " + segment);
                }
                String number = segment.substring(cursor + 1, close);
                try {
                    indexes.add(Integer.parseInt(number));
                } catch (NumberFormatException e) {
                    throw new CommandException(ErrorCode.PARSE_ERROR, "invalid array index: " + number);
                }
                cursor = close + 1;
            } else {
                property.append(ch);
                cursor++;
            }
        }
        if (property.length() == 0 && indexes.isEmpty()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "invalid path segment: " + segment);
        }
        return new PathToken(property.length() == 0 ? null : property.toString(), indexes);
    }

    private static final class PathToken {
        private final String property;
        private final List<Integer> indexes;

        private PathToken(String property, List<Integer> indexes) {
            this.property = property;
            this.indexes = indexes;
        }
    }
}
